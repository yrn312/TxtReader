package com.example.txtreader.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class BookItem(
    @Embedded val book: Book,
    val chapterCount: Int
)

@Dao
interface BookDao {
    @Insert
    suspend fun insert(b: Book): Long

    @Query("SELECT * FROM Book WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Query(
        "SELECT b.*, (SELECT COUNT(*) FROM Chapter c WHERE c.bookId = b.id) AS chapterCount " +
            "FROM Book b ORDER BY CASE WHEN b.lastReadAt = 0 THEN b.addedAt ELSE b.lastReadAt END DESC"
    )
    fun observeAll(): Flow<List<BookItem>>

    @Query("UPDATE Book SET lastOffset = :pos, lastSnippet = :snippet, lastReadAt = :t WHERE id = :id")
    suspend fun savePosition(id: Long, pos: Long, snippet: String, t: Long)

    @Query("UPDATE Book SET encoding = :enc, dataStart = :ds, indexed = 0, lastOffset = :ds, lastSnippet = '' WHERE id = :id")
    suspend fun resetEncoding(id: Long, enc: String, ds: Int)

    @Query("UPDATE Book SET indexed = :v WHERE id = :id")
    suspend fun setIndexed(id: Long, v: Boolean)

    @Query("DELETE FROM Book WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChapterDao {
    @Insert
    suspend fun insertAll(list: List<Chapter>)

    @Insert
    suspend fun insert(c: Chapter): Long

    @Query("SELECT * FROM Chapter WHERE bookId = :bookId ORDER BY pos")
    suspend fun all(bookId: Long): List<Chapter>

    @Query("SELECT * FROM Chapter WHERE bookId = :bookId AND pos <= :pos ORDER BY pos DESC LIMIT 1")
    suspend fun at(bookId: Long, pos: Long): Chapter?

    @Query("DELETE FROM Chapter WHERE bookId = :bookId AND manual = 0")
    suspend fun deleteAuto(bookId: Long)

    @Query("DELETE FROM Chapter WHERE bookId = :bookId")
    suspend fun deleteAll(bookId: Long)

    @Query("UPDATE Chapter SET title = :title, manual = 1 WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM Chapter WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BookmarkDao {
    @Insert
    suspend fun insert(m: Bookmark): Long

    @Query("SELECT * FROM Bookmark WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun list(bookId: Long): List<Bookmark>

    @Query("SELECT COUNT(*) FROM Bookmark WHERE bookId = :bookId AND pos = :pos")
    suspend fun exists(bookId: Long, pos: Long): Int

    @Query("DELETE FROM Bookmark WHERE id = :id")
    suspend fun delete(id: Long)
}
