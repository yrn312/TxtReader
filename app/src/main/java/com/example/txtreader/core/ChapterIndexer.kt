package com.example.txtreader.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.regex.PatternSyntaxException

/**
 * Chapter detection, three stages:
 *  1. coarse filter (line length, first character after stripping decoration, trailing punctuation)
 *  2. fine regex match on the normalized title line
 *  3. table-of-contents clusters are dropped, then numbering gaps (e.g. chapter 1, 3 -> 2 missing)
 *     are repaired by a targeted relaxed search between the two neighbouring chapters.
 * Files are streamed in 1MB chunks, never loaded whole.
 */
object ChapterIndexer {
    private const val CHUNK = 1 shl 20
    private const val MAX_LINE = 64 * 1024
    private const val SECTION_CHARS = 8000
    private const val MAX_TITLE_CHARS = 50
    private const val RECOVER_TITLE_CHARS = 80
    private const val MAX_RECOVER_GAP = 30
    private const val MAX_RECOVER_REGION = 8L * 1024 * 1024
    private const val COARSE_FIRST = "第CcSs序楔引前后後尾终終番附卷PpEe"
    private const val ZW = "\u200B\u200C\u200D\u200E\u200F\u2060\uFEFF"
    private const val OPENERS = "【[《〈(（「『"
    private const val CLOSERS = "】]》〉)）」』"
    private const val DECOR = "#*=-~·•—_"

    private const val NUM = "[0-9０-９零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+"

    val DEFAULT_RULES: List<String> = listOf(
        "^第\\s*$NUM\\s*[章节節话話](?:\\s*[:：、.\\-—·]?\\s*.*)?$",
        "^第\\s*$NUM\\s*[回卷集部篇幕](?:[\\s:：、.\\-—·].*)?$",
        "^(?i:chapter)\\s*(?:[0-9]+|[IVXLCivxlc]+|(?i:one|two|three|four|five|six|seven|eight|nine|ten))\\b(?:\\s*[:.\\-—]?\\s*.*)?$",
        "^(?:序章|序言|序|楔子|引子|前言|后记|後記|尾声|尾聲|终章|終章|番外|后序|附录)(?:[\\s:：、.\\-—·].*)?$",
        "^(?i:prologue|epilogue)(?:[\\s:.\\-—].*)?$"
    )

    private val HEAD_CN = Regex("^第\\s*($NUM)\\s*[章节節回话話]")
    private val HEAD_EN = Regex("^(?i:chapter)\\s*([0-9]+)")

    class Found(val title: String, val pos: Long)

    /** [recovered]: chapters repaired by the numbering pass; [missing]: numbers still not found. */
    class ScanResult(
        val chapters: List<Found>,
        val auto: Boolean,
        val recovered: Int = 0,
        val missing: List<String> = emptyList()
    )

    class Rules(val regexes: List<Regex>, val custom: Boolean)

    private class Cand(val title: String, val pos: Long, val endAbs: Long)
    private class Norm(val display: String, val core: String)

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

    // ---------- normalization ----------

    /**
     * display = cleaned line (invisible chars removed, full-width/nbsp spaces -> ' ', trimmed)
     * core    = display without surrounding decoration such as 【 】《 》# ** and a leading "正文".
     */
    private fun normalize(raw: String): Norm? {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (ZW.indexOf(c) >= 0) continue
            if (c == '\u3000' || c == '\u00A0') sb.append(' ') else sb.append(c)
        }
        val disp = sb.toString().trim { it <= ' ' }
        if (disp.isEmpty()) return null
        var s = 0
        var e = disp.length
        var hadOpen = false
        while (s < e) {
            val c = disp[s]
            if (c <= ' ' || DECOR.indexOf(c) >= 0) s++
            else if (OPENERS.indexOf(c) >= 0) { hadOpen = true; s++ }
            else break
        }
        if (disp.startsWith("正文", s)) {
            var k = s + 2
            while (k < e && disp[k] <= ' ') k++
            if (k < e && disp[k] == '第') s = k
        }
        while (e > s) {
            val c = disp[e - 1]
            if (c <= ' ' || DECOR.indexOf(c) >= 0 || (hadOpen && CLOSERS.indexOf(c) >= 0)) e-- else break
        }
        if (e <= s) return null
        return Norm(disp, disp.substring(s, e))
    }

    private fun rejectEnd(core: String): Boolean {
        val c = core[core.length - 1]
        if ("。，、；,;".indexOf(c) >= 0) return true
        // a trailing colon / dot is fine for very short titles ("第二章：", "Chapter 2.") but not for sentences
        if ("：:.".indexOf(c) >= 0) return core.length > 14
        return false
    }

    private fun accept(core: String, rules: Rules): Boolean {
        if (core.length > MAX_TITLE_CHARS) return false                          // coarse: length
        if (!rules.custom && COARSE_FIRST.indexOf(core[0]) < 0) return false     // coarse: first char
        if (rejectEnd(core)) return false                                        // coarse: sentence punctuation
        return rules.regexes.any { it.matches(core) }                            // fine: regex
    }

    // ---------- chapter numbers ----------

    private fun digitOf(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in '０'..'９' -> c - '０'
        '零', '〇' -> 0
        '一', '壹' -> 1
        '二', '两', '贰' -> 2
        '三', '叁' -> 3
        '四', '肆' -> 4
        '五', '伍' -> 5
        '六', '陆' -> 6
        '七', '柒' -> 7
        '八', '捌' -> 8
        '九', '玖' -> 9
        else -> null
    }

    private fun cnToInt(s: String): Int? {
        if (s.isEmpty()) return null
        var allDigit = true
        val sb = StringBuilder()
        for (c in s) {
            val d = digitOf(c)
            if (d == null) { allDigit = false; break }
            sb.append(d)
        }
        if (allDigit) return sb.toString().toIntOrNull()
        var result = 0
        var section = 0
        var num = 0
        for (c in s) {
            val d = digitOf(c)
            if (d != null) { num = d; continue }
            when (c) {
                '十', '拾' -> { section += (if (num == 0) 1 else num) * 10; num = 0 }
                '百', '佰' -> { section += (if (num == 0) 1 else num) * 100; num = 0 }
                '千', '仟' -> { section += (if (num == 0) 1 else num) * 1000; num = 0 }
                '万' -> { result += (section + num) * 10000; section = 0; num = 0 }
                else -> return null
            }
        }
        return result + section + num
    }

    private fun headNum(core: String): Int? {
        val cn = HEAD_CN.find(core)
        if (cn != null) return cnToInt(cn.groupValues[1])
        val en = HEAD_EN.find(core)
        if (en != null) return en.groupValues[1].toIntOrNull()
        return null
    }

    // ---------- streaming line reader ----------

    private fun countChars(kind: Int, d: ByteArray, s: Int, e: Int): Int {
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

    /** Calls [cb] (absolute line start, buffer, start, end excl. newline) for every line in [from, end). */
    private suspend fun forEachLine(
        bf: BookFile,
        from: Long,
        end: Long,
        onProgress: ((Long) -> Unit)?,
        cb: (Long, ByteArray, Int, Int) -> Unit
    ) {
        val unit = bf.unit
        val le = bf.book.encoding == "UTF-16LE"

        fun findNl(d: ByteArray, start: Int): Int {
            if (unit == 1) {
                var j = start
                while (j < d.size) {
                    if (d[j] == 10.toByte()) return j
                    j++
                }
                return -1
            }
            var j = start
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

        var filePos = from
        var carry = ByteArray(0)
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
                        cb(base + i, data, i, data.size)
                        i = data.size
                    } else if (rest > MAX_LINE) {
                        val cut = rest / unit * unit
                        cb(base + i, data, i, i + cut)
                        i += cut
                    }
                    break
                }
                cb(base + i, data, i, nl)
                i = nl + unit
            }
            carry = if (i < data.size) data.copyOfRange(i, data.size) else ByteArray(0)
            filePos += chunk.size
            onProgress?.invoke(filePos)
            if (atEof) break
        }
    }

    // ---------- main scan ----------

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
        val maxLineBytes = if (kind == 0) MAX_TITLE_CHARS * 4 + 16 else MAX_TITLE_CHARS * 2 + 16
        val cs = bf.charset
        val end = minOf(bf.size, limitBytes)
        val total = maxOf(1L, end - bf.dataStart)

        val cands = ArrayList<Cand>()
        val sections = ArrayList<Long>().apply { add(bf.dataStart) }
        var charsSince = 0L
        var lastPct = -1

        forEachLine(bf, bf.dataStart, end, { fp ->
            val pct = ((fp - bf.dataStart) * 100 / total).toInt().coerceIn(0, 100)
            if (pct != lastPct) {
                lastPct = pct
                onProgress(pct)
            }
        }) { abs, d, s, e ->
            if (charsSince >= SECTION_CHARS) {
                sections.add(abs)
                charsSince = 0
            }
            val len = e - s
            charsSince += countChars(kind, d, s, e)
            if (len > 0 && len <= maxLineBytes) {
                val n = normalize(String(d, s, len, cs))
                if (n != null && accept(n.core, rules)) {
                    cands.add(Cand(n.display, abs, abs + len + unit))
                }
            }
        }

        // Adjacent candidates (nothing but line breaks between them):
        //  3 or more in a row = a table of contents -> dropped
        //  2 in a row = duplicated heading -> keep the first (both when the first is a volume title)
        val adj = 4 * unit
        var kept = ArrayList<Found>()
        var i = 0
        while (i < cands.size) {
            var j = i + 1
            while (j < cands.size && cands[j].pos - cands[j - 1].endAbs <= adj) j++
            val run = j - i
            if (run >= 3) {
                // contents table
            } else if (run == 2) {
                kept.add(Found(cands[i].title, cands[i].pos))
                if (isVolume(cands[i].title)) kept.add(Found(cands[i + 1].title, cands[i + 1].pos))
            } else {
                kept.add(Found(cands[i].title, cands[i].pos))
            }
            i = j
        }
        if (kept.isEmpty() && cands.isNotEmpty()) {
            kept = ArrayList(cands.map { Found(it.title, it.pos) })
        }

        if (kept.isEmpty()) {
            // no chapter titles: split by character count
            return ScanResult(sections.mapIndexed { idx, p -> Found("第${idx + 1}节", p) }, true)
        }

        val rec = recover(bf, kept, end)
        val all = rec.first
        val out = ArrayList<Found>()
        if (all[0].pos - bf.dataStart > 300) out.add(Found("开头", bf.dataStart))
        out.addAll(all)
        return ScanResult(out, false, rec.second, rec.third)
    }

    /**
     * Numbering repair: when numbered chapters jump (… 1, 3 …) the missing numbers are searched
     * between the two neighbours with relaxed rules (any punctuation, any distance, <= 80 chars).
     * Returns (merged list, recovered count, numbers still missing).
     */
    private suspend fun recover(
        bf: BookFile,
        list: List<Found>,
        end: Long
    ): Triple<List<Found>, Int, List<String>> {
        class Need(val from: Long, val to: Long, val nums: MutableSet<Int>)

        val needs = ArrayList<Need>()
        var prevNum = -1
        var prevPos = bf.dataStart
        for (f in list) {
            val nm = normalize(f.title) ?: continue
            val n = headNum(nm.core) ?: continue
            if (prevNum >= 0 && n > prevNum + 1 && n - prevNum - 1 <= MAX_RECOVER_GAP) {
                needs.add(Need(prevPos, f.pos, (prevNum + 1 until n).toMutableSet()))
            } else if (prevNum < 0 && n in 2..12) {
                needs.add(Need(bf.dataStart, f.pos, (1 until n).toMutableSet()))
            }
            prevNum = n
            prevPos = f.pos
        }
        if (needs.isEmpty()) return Triple(list, 0, emptyList())

        val cs = bf.charset
        val maxBytes = if (bf.isUtf8) RECOVER_TITLE_CHARS * 4 + 16 else RECOVER_TITLE_CHARS * 2 + 16
        val added = ArrayList<Found>()
        val missing = ArrayList<String>()
        for (need in needs) {
            val got = HashMap<Int, Found>()
            val regionEnd = minOf(need.to, end, need.from + MAX_RECOVER_REGION)
            if (regionEnd > need.from) {
                forEachLine(bf, need.from, regionEnd, null) { abs, d, s, e ->
                    val len = e - s
                    if (len > 0 && len <= maxBytes && abs > need.from) {
                        val nm = normalize(String(d, s, len, cs))
                        if (nm != null && nm.core.length <= RECOVER_TITLE_CHARS && !rejectEnd(nm.core)) {
                            val n = headNum(nm.core)
                            if (n != null && n in need.nums && !got.containsKey(n)) {
                                got[n] = Found(nm.display, abs)
                            }
                        }
                    }
                }
            }
            added.addAll(got.values)
            for (n in need.nums.sorted()) if (!got.containsKey(n)) missing.add("第${n}章")
        }
        val merged = (list + added).sortedBy { it.pos }
        return Triple(merged, added.size, missing)
    }
}
