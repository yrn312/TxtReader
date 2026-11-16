package com.example.txtreader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "Book")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val path: String,
    val size: Long,
    val encoding: String,
    /** BOM length in bytes; text data begins here. */
    val dataStart: Int = 0,
    val indexed: Boolean = false,
    /** Byte offset of the start of the last read page. */
    val lastOffset: Long = 0,
    /** First non-blank characters of the last read page, used to verify the position on restore. */
    val lastSnippet: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0
)

@Entity(
    tableName = "Chapter",
    indices = [Index(value = ["bookId", "pos"])],
    foreignKeys = [ForeignKey(
        entity = Book::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    /** Byte offset of the chapter title line. */
    val pos: Long,
    val manual: Boolean = false
)

@Entity(
    tableName = "Bookmark",
    indices = [Index(value = ["bookId"])],
    foreignKeys = [ForeignKey(
        entity = Book::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pos: Long,
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long
)
