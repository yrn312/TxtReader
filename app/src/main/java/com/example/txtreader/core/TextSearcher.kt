package com.example.txtreader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class SearchHit(val pos: Long, val snippet: String, val matchStart: Int, val matchLen: Int)

/**
 * Streaming full-text search. The file is scanned in 256KB windows; each window also reads a few
 * extra bytes so a match crossing the window boundary is never cut. Windows start on line
 * boundaries, hits are only accepted from the "core" part of a window (no duplicates).
 * [next] is pull based: it scans only until enough hits for one page were found, so results
 * load lazily; cancelling the calling coroutine stops the scan.
 */
class TextSearcher(private val bf: BookFile, query: String) {
    private val q = query
    private var pos = bf.dataStart
    private val queue = ArrayDeque<SearchHit>()

    var finished = false
        private set

    suspend fun next(limit: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        val out = ArrayList<SearchHit>()
        while (out.size < limit) {
            if (queue.isNotEmpty()) {
                out.add(queue.removeFirst())
                continue
            }
            if (pos >= bf.size) break
            scanWindow()
        }
        if (queue.isEmpty() && pos >= bf.size) finished = true
        out
    }

    private suspend fun scanWindow() {
        yield()
        val unit = bf.unit
        val over = minOf(q.length * 4 + 16, 4096)
        val bytes = bf.readBytes(pos, CORE + over)
        if (bytes.isEmpty()) {
            pos = bf.size
            return
        }
        val atEnd = pos + bytes.size >= bf.size
        var coreLen: Int
        if (atEnd && bytes.size <= CORE) {
            coreLen = bytes.size
        } else {
            var i = minOf(CORE, bytes.size) - unit
            i -= i % unit
            coreLen = -1
            while (i >= 0) {
                if (bf.isNewline(bytes, i)) {
                    coreLen = i + unit
                    break
                }
                i -= unit
            }
            if (coreLen <= 0) coreLen = minOf(CORE, bytes.size) / unit * unit
            if (coreLen <= 0) coreLen = bytes.size
        }

        val text = Codec.decodeBulk(bytes, bytes.size, bf.charset)
        var idx = text.indexOf(q, 0, true)
        var prevChar = 0
        var prevBytes = 0L
        while (idx >= 0) {
            prevBytes += text.substring(prevChar, idx).toByteArray(bf.charset).size
            prevChar = idx
            if (prevBytes >= coreLen) break
            val s = maxOf(0, idx - 15)
            val e = minOf(text.length, idx + q.length + 25)
            val sn = CharArray(e - s) { k ->
                val c = text[s + k]
                if (c.isWhitespace() || c < ' ') ' ' else c
            }
            queue.add(SearchHit(pos + prevBytes, String(sn), idx - s, q.length))
            idx = text.indexOf(q, idx + q.length, true)
        }
        pos += coreLen
    }

    companion object {
        private const val CORE = 256 * 1024
    }
}
