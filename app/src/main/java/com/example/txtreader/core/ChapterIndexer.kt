package com.example.txtreader.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.regex.PatternSyntaxException

/**
 * Two-stage chapter detection:
 *  1. coarse filter on raw bytes/first char (line length, first character, trailing punctuation)
 *  2. fine regex match on the decoded title line.
 * Files are streamed in 1MB chunks, never loaded whole.
 */
object ChapterIndexer {
    private const val CHUNK = 1 shl 20
    private const val MAX_LINE = 64 * 1024
    private const val SECTION_CHARS = 8000
    private const val MAX_TITLE_CHARS = 50
    private const val END_PUNCT = "。，、；：！？,;:!?…\"“”‘’'."
    private const val COARSE_FIRST = "第CcSs序楔引前后後尾终終番附卷PpEe"

    private const val NUM = "[0-9０-９零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+"

    val DEFAULT_RULES: List<String> = listOf(
        "^第\\s*$NUM\\s*[章节節话話](?:\\s*[:：、.\\-—·]?\\s*.*)?$",
        "^第\\s*$NUM\\s*[回卷集部篇幕](?:[\\s:：、.\\-—·].*)?$",
        "^(?:Chapter|CHAPTER|Chap\\.?)\\s*(?:[0-9]+|[IVXLCivxlc]+|(?:One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten))(?:\\s*[:.\\-—]?\\s*.*)?$",
        "^(?:序章|序言|序|楔子|引子|前言|后记|後記|尾声|尾聲|终章|終章|番外|后序|附录)(?:[\\s:：、.\\-—·].*)?$",
        "^(?:Prologue|Epilogue|PROLOGUE|EPILOGUE)(?:[\\s:.\\-—].*)?$"
    )

    class Found(val title: String, val pos: Long)
    class ScanResult(val chapters: List<Found>, val auto: Boolean)
    class Rules(val regexes: List<Regex>, val custom: Boolean)

    /** Empty text = built-in rules. Throws IllegalArgumentException on an invalid regex. */
    fun parseRules(text: String): Rules {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return Rules(DEFAULT_RULES.map { Regex(it) }, false)
        val list = lines.mapIndexed { i, l ->
            try {
                Regex(l)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("第${i + 1}条规则无效：${e.description}")
            }
        }
        return Rules(list, true)
    }

    private fun isVolume(title: String): Boolean =
        title.startsWith("第") && title.take(10).let { it.contains('卷') || it.contains('部') }

    suspend fun scan(
        bf: BookFile,
        rules: Rules,
        limitBytes: Long = Long.MAX_VALUE,
        onProgress: (Int) -> Unit = {}
    ): ScanResult {
        val kind = when {
            bf.isUtf16 -> 2
            bf.isUtf8 -> 0
            else -> 1
        }
        val unit = bf.unit
        val le = bf.book.encoding == "UTF-16LE"
        val maxLineBytes = if (kind == 0) MAX_TITLE_CHARS * 3 + 8 else MAX_TITLE_CHARS * 2 + 8
        val minGap = if (kind == 0) 150 else 100
        val cs = bf.charset
        val end = minOf(bf.size, limitBytes)

        val found = ArrayList<Found>()
        val sections = ArrayList<Long>().apply { add(bf.dataStart) }
        var charsSince = 0L
        var lastCandPos = Long.MIN_VALUE / 2
        var lastCandTitle = ""

        fun chars(d: ByteArray, s: Int, e: Int): Int {
            var c = 0
            when (kind) {
                0 -> for (k in s until e) if ((d[k].toInt() and 0xC0) != 0x80) c++
                2 -> c = (e - s) / 2
                else -> {
                    var hi = 0
                    for (k in s until e) if (d[k] >= 0) c++ else hi++
                    c += (hi + 1) / 2
                }
            }
            return c
        }

        fun findNl(d: ByteArray, from: Int): Int {
            if (unit == 1) {
                var j = from
                while (j < d.size) {
                    if (d[j] == 10.toByte()) return j
                    j++
                }
                return -1
            }
            var j = from
            while (j + 1 < d.size) {
                if (le) {
                    if (d[j] == 10.toByte() && d[j + 1] == 0.toByte()) return j
                } else {
                    if (d[j] == 0.toByte() && d[j + 1] == 10.toByte()) return j
                }
                j += 2
            }
            return -1
        }

        fun line(abs: Long, d: ByteArray, s: Int, e: Int) {
            if (charsSince >= SECTION_CHARS) {
                sections.add(abs)
                charsSince = 0
            }
            val len = e - s
            charsSince += chars(d, s, e)
            if (len <= 0 || len > maxLineBytes) return                       // coarse: byte length
            val t = String(d, s, len, cs).trim {
                it <= ' ' || it == '\u3000' || it == '\u00A0' || it == '\uFEFF'
            }
            if (t.isEmpty() || t.length > MAX_TITLE_CHARS) return            // coarse: char length
            if (!rules.custom && COARSE_FIRST.indexOf(t[0]) < 0) return     // coarse: first char
            if (END_PUNCT.indexOf(t[t.length - 1]) >= 0) return              // no trailing punctuation
            if (rules.regexes.none { it.matches(t) }) return                 // fine: regex
            val tooClose = abs - lastCandPos < minGap && !isVolume(lastCandTitle)
            lastCandPos = abs
            lastCandTitle = t
            if (tooClose) return                                             // adjacent titles too close
            found.add(Found(t, abs))
        }

        var filePos = bf.dataStart
        var carry = ByteArray(0)
        var lastPct = -1
        val total = maxOf(1L, end - bf.dataStart)
        while (true) {
            currentCoroutineContext().ensureActive()
            val toRead = minOf(CHUNK.toLong(), end - filePos).coerceAtLeast(0L).toInt()
            val chunk = if (toRead > 0) bf.readBytes(filePos, toRead) else ByteArray(0)
            val atEof = filePos + chunk.size >= end
            val data = if (carry.isEmpty()) chunk else carry + chunk
            val base = filePos - carry.size
            var i = 0
            while (i < data.size) {
                val nl = findNl(data, i)
                if (nl < 0) {
                    val rest = data.size - i
                    if (atEof) {
                        line(base + i, data, i, data.size)
                        i = data.size
                    } else if (rest > MAX_LINE) {
                        val cut = rest / unit * unit
                        line(base + i, data, i, i + cut)
                        i += cut
                    }
                    break
                }
                line(base + i, data, i, nl)
                i = nl + unit
            }
            carry = if (i < data.size) data.copyOfRange(i, data.size) else ByteArray(0)
            filePos += chunk.size
            val pct = ((filePos - bf.dataStart) * 100 / total).toInt().coerceIn(0, 100)
            if (pct != lastPct) {
                lastPct = pct
                onProgress(pct)
            }
            if (atEof) break
        }

        if (found.isEmpty()) {
            // no chapter titles: split by character count
            return ScanResult(sections.mapIndexed { idx, p -> Found("第${idx + 1}节", p) }, true)
        }
        val out = ArrayList<Found>()
        if (found[0].pos - bf.dataStart > 300) out.add(Found("开头", bf.dataStart))
        out.addAll(found)
        return ScanResult(out, false)
    }
}
