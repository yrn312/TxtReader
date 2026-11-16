package com.example.txtreader.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object Codec {
    val ENCODINGS = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GBK", "GB18030", "Big5")

    class Detected(val name: String, val dataStart: Int)

    /** Decoded block with an exact char index -> byte offset map. */
    class TextBlock(val chars: CharArray, val count: Int, val offs: IntArray, val consumed: Int) {
        fun text(): String = String(chars, 0, count)
    }

    private const val SIMP = "们这个来说时国会学对后点发过还开动里么为"
    private const val TRAD = "們這個來說時國會學對後點發過還開動裡麼為"

    fun bomLength(file: File, encoding: String): Int {
        RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(3)
            val n = raf.read(b)
            if (n >= 3 && encoding == "UTF-8" &&
                b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()
            ) return 3
            if (n >= 2 && encoding == "UTF-16LE" && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return 2
            if (n >= 2 && encoding == "UTF-16BE" && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return 2
        }
        return 0
    }

    fun detect(file: File): Detected {
        val len = file.length()
        if (len <= 0L) return Detected("UTF-8", 0)
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(len, 4L).toInt())
            raf.readFully(head)
            if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte())
                return Detected("UTF-8", 3)
            if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Detected("UTF-16LE", 2)
            if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Detected("UTF-16BE", 2)

            val sampleLen = 32 * 1024
            val positions = listOf(0L, len / 2, len - sampleLen)
                .map { it.coerceIn(0L, maxOf(0L, len - 1)) }
                .distinct()
            val samples = ArrayList<ByteArray>()
            for (p in positions) {
                val n = minOf(sampleLen.toLong(), len - p).toInt()
                if (n <= 0) continue
                val b = ByteArray(n)
                raf.seek(p)
                raf.readFully(b)
                var start = 0
                if (p > 0) {
                    // start right after a newline so we begin on a character boundary
                    val nl = b.indexOf(0x0A.toByte())
                    start = if (nl >= 0) nl + 1 else n
                }
                if (start < n) samples.add(b.copyOfRange(start, n))
            }
            if (samples.isEmpty()) return Detected("UTF-8", 0)

            // UTF-16 without BOM: lots of zero bytes on one parity
            val s0 = samples[0]
            val m = minOf(s0.size, 4096)
            var even = 0
            var odd = 0
            for (i in 0 until m) if (s0[i] == 0.toByte()) { if (i % 2 == 0) even++ else odd++ }
            if (m >= 8) {
                if (odd > m / 8 && even < m / 64) return Detected("UTF-16LE", 0)
                if (even > m / 8 && odd < m / 64) return Detected("UTF-16BE", 0)
            }

            if (samples.all { decodeStrict(it, Charsets.UTF_8) != null }) return Detected("UTF-8", 0)

            val gb18030 = Charset.forName("GB18030")
            val big5 = Charset.forName("Big5")
            val gbDec = samples.map { decodeStrict(it, gb18030) }
            val bigDec = samples.map { decodeStrict(it, big5) }
            val gbOk = gbDec.all { it != null }
            val bigOk = bigDec.all { it != null }
            val gbk = Charset.forName("GBK")
            val gbName = if (samples.all { decodeStrict(it, gbk) != null }) "GBK" else "GB18030"
            return when {
                gbOk && !bigOk -> Detected(gbName, 0)
                bigOk && !gbOk -> Detected("Big5", 0)
                gbOk && bigOk -> {
                    var simp = 0
                    for (s in gbDec) if (s != null) simp += s.count { it in SIMP }
                    var trad = 0
                    for (s in bigDec) if (s != null) trad += s.count { it in TRAD }
                    if (trad > simp) Detected("Big5", 0) else Detected(gbName, 0)
                }
                else -> Detected("GB18030", 0)
            }
        }
    }

    private fun decodeStrict(bytes: ByteArray, cs: Charset): String? {
        val dec = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val inb = ByteBuffer.wrap(bytes)
        val out = CharBuffer.allocate(bytes.size + 2)
        val r = dec.decode(inb, out, false)
        if (r.isError) return null
        out.flip()
        return out.toString()
    }

    private fun clean(c: Char): Char =
        if (c == '\r' || (c < ' ' && c != '\n' && c != '\t')) ' ' else c

    /**
     * Decodes [len] bytes of [buf]. A trailing partial character is left undecoded
     * (reported through [TextBlock.consumed]). Malformed bytes become U+FFFD.
     */
    fun decodeBlock(buf: ByteArray, len: Int, cs: Charset): TextBlock {
        val dec = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val inb = ByteBuffer.wrap(buf, 0, len)
        val chars = CharArray(len + 2)
        val offs = IntArray(len + 3)
        var n = 0
        val one = CharBuffer.allocate(1)
        val two = CharBuffer.allocate(2)
        while (inb.hasRemaining()) {
            val start = inb.position()
            one.clear()
            val r = dec.decode(inb, one, false)
            if (one.position() == 1) {
                chars[n] = clean(one.get(0))
                offs[n] = start
                n++
            } else if (r.isOverflow) {
                two.clear()
                dec.decode(inb, two, false)
                val k = two.position()
                if (k == 0) break
                for (t in 0 until k) {
                    chars[n] = clean(two.get(t))
                    offs[n] = start
                    n++
                }
            } else {
                break
            }
        }
        offs[n] = inb.position()
        return TextBlock(chars, n, offs, inb.position())
    }

    /** Fast bulk decode without offset mapping (used by search). */
    fun decodeBulk(buf: ByteArray, len: Int, cs: Charset): String {
        val dec = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val inb = ByteBuffer.wrap(buf, 0, len)
        val out = CharBuffer.allocate(len + 2)
        dec.decode(inb, out, false)
        out.flip()
        return out.toString()
    }

    /** First [n] non-whitespace characters. */
    fun snippetOf(text: CharSequence, n: Int): String {
        val sb = StringBuilder()
        for (c in text) {
            if (!c.isWhitespace()) {
                sb.append(c)
                if (sb.length >= n) break
            }
        }
        return sb.toString()
    }
}
