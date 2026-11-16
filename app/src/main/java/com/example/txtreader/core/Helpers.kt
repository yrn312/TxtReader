package com.example.txtreader.core

import com.example.txtreader.data.AppDatabase
import com.example.txtreader.data.Book
import com.example.txtreader.data.Bookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

fun pctText(pos: Long, size: Long): String =
    String.format(Locale.US, "%.1f%%", pos * 100.0 / maxOf(1L, size))

object Bookmarks {
    /** Adds a bookmark at [pos]; returns false if one already exists there. */
    suspend fun add(db: AppDatabase, book: Book, pos: Long): Boolean = withContext(Dispatchers.IO) {
        if (db.bookmarkDao().exists(book.id, pos) > 0) return@withContext false
        BookFile(book).use { bf ->
            val snippet = bf.decode(pos, 400).text().replace(Regex("\\s+"), " ").trim().take(40)
            val ch = db.chapterDao().at(book.id, pos)
            db.bookmarkDao().insert(
                Bookmark(
                    bookId = book.id,
                    pos = pos,
                    chapterTitle = ch?.title ?: book.title,
                    snippet = snippet,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        true
    }
}
