package com.example.txtreader.core

import com.example.txtreader.data.Book
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset

/** Random-access, block-wise view of a book file. The whole file is never held in memory. */
class BookFile(val book: Book) : Closeable {
    private val raf = RandomAccessFile(File(book.path), "r")
    val size: Long = raf.length()
    val charset: Charset = Charset.forName(book.encoding)
    val dataStart: Long = book.dataStart.toLong()
    val isUtf16: Boolean = book.encoding.startsWith("UTF-16")
    val isUtf8: Boolean = book.encoding == "UTF-8"
    private val le: Boolean = book.encoding == "UTF-16LE"
    val unit: Int = if (isUtf16) 2 else 1

    fun readBytes(pos: Long, len: Int): ByteArray {
        if (pos >= size || len <= 0) return ByteArray(0)
        val n = minOf(len.toLong(), size - pos).toInt()
        val bb = ByteBuffer.allocate(n)
        val ch = raf.channel
        var p = pos
        while (bb.hasRemaining()) {
            val r = ch.read(bb, p)
            if (r < 0) break
            p += r
        }
        return if (bb.position() == n) bb.array() else bb.array().copyOf(bb.position())
    }

    fun decode(pos: Long, len: Int): Codec.TextBlock {
        val b = readBytes(pos, len)
        return Codec.decodeBlock(b, b.size, charset)
    }

    fun isNewline(b: ByteArray, i: Int): Boolean {
        if (!isUtf16) return b[i] == 0x0A.toByte()
        if (i + 1 >= b.size) return false
        return if (le) b[i] == 0x0A.toByte() && b[i + 1] == 0.toByte()
        else b[i] == 0.toByte() && b[i + 1] == 0x0A.toByte()
    }

    private fun alignToChar(pos: Long): Long {
        if (isUtf16) return if ((pos - dataStart) % 2L != 0L) pos + 1 else pos
        if (isUtf8) {
            var p = pos
            val b = readBytes(p, 4)
            var i = 0
            while (i < b.size && i < 3 && (b[i].toInt() and 0xC0) == 0x80) { i++; p++ }
            return p
        }
        return pos
    }

    /** Start of the paragraph (line) containing [pos]; searches back at most [maxBack] bytes. */
    fun paragraphStart(pos: Long, maxBack: Int = 64 * 1024): Long {
        if (pos <= dataStart) return dataStart
        val from = maxOf(dataStart, pos - maxBack)
        val bytes = readBytes(from, (pos - from).toInt())
        var i = bytes.size - unit
        if (isUtf16 && (i - 0) % 2 != 0) i--
        while (i >= 0) {
            if (isNewline(bytes, i)) return from + i + unit
            i -= unit
        }
        return if (from == dataStart) dataStart else alignToChar(from)
    }

    /**
     * Verifies that the text at [off] still starts with [snippet]. Returns [off] if so,
     * a nearby relocated offset if the snippet is found within +-64KB, or -1.
     */
    fun verifyOrRelocate(off: Long, snippet: String): Long {
        if (snippet.isEmpty()) return off
        val head = decode(off, 800).text()
        if (Codec.snippetOf(head, snippet.length) == snippet) return off
        val prefix = snippet.take(6)
        val from = maxOf(dataStart, off - 65536)
        val tb = decode(from, 131072)
        val idx = tb.text().indexOf(prefix)
        return if (idx >= 0) from + tb.offs[idx] else -1L
    }

    override fun close() {
        try { raf.close() } catch (_: Exception) { }
    }
}
