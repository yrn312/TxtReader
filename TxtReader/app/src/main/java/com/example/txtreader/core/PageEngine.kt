package com.example.txtreader.core

import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PageInfo(val start: Long, val end: Long)

class Rendered(val info: PageInfo, val text: String, val layout: StaticLayout)

/**
 * Pagination pipeline:
 *   64KB block -> decode (char index -> byte offset map) -> StaticLayout line breaking
 *   -> cut pages by height -> page start/end stored as byte offsets.
 * A contiguous list of page boundaries is cached around the reading position;
 * the page after the current one is extended on demand and the neighbours (+-2) are preloaded.
 */
class PageEngine(private val bf: BookFile) {
    companion object {
        const val BLOCK = 64 * 1024
        const val BACK_BYTES = 16 * 1024
    }

    private var width = 100
    private var height = 100
    private var textSizePx = 48f
    private var color = Color.BLACK
    private var lineMult = 1.5f

    private val pages = ArrayList<PageInfo>()
    private var cur = 0
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<Long, Rendered>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Rendered>?): Boolean = this.size > 12
    }

    suspend fun configure(w: Int, h: Int, textSizePx: Float, color: Int, lineMult: Float) {
        mutex.withLock {
            this.width = maxOf(1, w)
            this.height = maxOf(1, h)
            this.textSizePx = textSizePx
            this.color = color
            this.lineMult = lineMult
            cache.clear()
        }
    }

    fun buildLayout(text: CharSequence): StaticLayout {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = textSizePx
        p.color = color
        return StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setLineSpacing(0f, lineMult)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    /** Lays out bytes [from, to) and cuts them into pages. */
    private fun paginate(from: Long, to: Long, dropTail: Boolean, forceEnd: Boolean): List<PageInfo> {
        val want = minOf(to - from, 4L * 1024 * 1024).toInt()
        if (want <= 0) return emptyList()
        val tb = bf.decode(from, want)
        if (tb.count == 0) return emptyList()
        val layout = buildLayout(String(tb.chars, 0, tb.count))
        val out = ArrayList<PageInfo>()
        val lc = layout.lineCount
        var line = 0
        while (line < lc) {
            val top = layout.getLineTop(line)
            var end = line
            while (end < lc && layout.getLineBottom(end) - top <= height) end++
            if (end == line) end = line + 1
            val sChar = layout.getLineStart(line)
            val eChar = layout.getLineEnd(end - 1)
            if (eChar > sChar) {
                out.add(PageInfo(from + tb.offs[sChar], from + tb.offs[eChar]))
            }
            line = end
        }
        if (out.isEmpty()) return out
        if (dropTail && out.size > 1 && from + tb.consumed < bf.size) out.removeAt(out.size - 1)
        if (forceEnd) {
            val last = out[out.size - 1]
            if (last.end != to) out[out.size - 1] = PageInfo(last.start, to)
        }
        return out
    }

    private fun render(info: PageInfo): Rendered {
        cache[info.start]?.let { return it }
        val tb = bf.decode(info.start, (info.end - info.start).toInt())
        val text = String(tb.chars, 0, tb.count).trimEnd('\n')
        val r = Rendered(info, text, buildLayout(text))
        cache[info.start] = r
        return r
    }

    private fun extendForward(): Boolean {
        if (pages.isEmpty()) return false
        val from = pages[pages.size - 1].end
        if (from >= bf.size) return false
        val to = minOf(from + BLOCK, bf.size)
        val ps = paginate(from, to, to < bf.size, false)
        if (ps.isEmpty()) return false
        pages.addAll(ps)
        return true
    }

    private fun extendBackward(): Boolean {
        if (pages.isEmpty()) return false
        val first = pages[0].start
        if (first <= bf.dataStart) return false
        val approx = maxOf(bf.dataStart, first - BACK_BYTES)
        val b = bf.paragraphStart(approx)
        if (b >= first) return false
        val ps = paginate(b, first, false, true)
        if (ps.isEmpty()) return false
        pages.addAll(0, ps)
        cur += ps.size
        return true
    }

    /** Resets the page list and opens the page containing byte offset [offset]. */
    suspend fun open(offset: Long): Rendered? = mutex.withLock {
        withContext(Dispatchers.Default) {
            pages.clear()
            cache.clear()
            cur = 0
            if (bf.size <= bf.dataStart) return@withContext null
            val target = offset.coerceIn(bf.dataStart, bf.size - 1)
            var from = bf.paragraphStart(target)
            var found = -1
            while (true) {
                val to = minOf(from + BLOCK, bf.size)
                val ps = paginate(from, to, to < bf.size, false)
                if (ps.isEmpty()) break
                val startIdx = pages.size
                pages.addAll(ps)
                for (k in startIdx until pages.size) {
                    if (pages[k].start <= target && target < pages[k].end) {
                        found = k
                        break
                    }
                }
                if (found >= 0) break
                from = pages[pages.size - 1].end
                if (from >= bf.size) break
            }
            if (pages.isEmpty()) return@withContext null
            cur = if (found >= 0) found else pages.size - 1
            render(pages[cur])
        }
    }

    suspend fun next(): Rendered? = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (cur + 1 >= pages.size) extendForward()
            if (cur + 1 >= pages.size) return@withContext null
            cur++
            render(pages[cur])
        }
    }

    suspend fun prev(): Rendered? = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (cur == 0) extendBackward()
            if (cur == 0) return@withContext null
            cur--
            render(pages[cur])
        }
    }

    /** Extends the page list and warms the render cache for the pages around the current one (+-2). */
    suspend fun preload() {
        mutex.withLock {
            withContext(Dispatchers.Default) {
                if (pages.isEmpty()) return@withContext
                while (pages.size - 1 - cur < 2) {
                    if (!extendForward()) break
                }
                while (cur < 2) {
                    if (!extendBackward()) break
                }
                for (k in (cur - 2)..(cur + 2)) {
                    if (k >= 0 && k < pages.size) render(pages[k])
                }
            }
        }
    }
}
