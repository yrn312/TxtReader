package com.example.txtreader.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.example.txtreader.data.AppDatabase
import com.example.txtreader.data.Book
import com.example.txtreader.data.Chapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** App-level manager: imports books and (re)builds chapter indexes in the background. */
class IndexManager(
    private val ctx: Context,
    private val db: AppDatabase,
    private val prefs: ReaderPrefs
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()

    /** bookId -> scan progress 0..100 while indexing. */
    val progress = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val importing = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private fun setProgress(id: Long, pct: Int) = progress.update { it + (id to pct) }
    private fun clearProgress(id: Long) = progress.update { it - id }

    fun import(uri: Uri) {
        scope.launch {
            var out: File? = null
            try {
                importing.value = "正在导入…"
                val cr = ctx.contentResolver
                var name = "未命名"
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
                val dir = File(ctx.filesDir, "books").apply { mkdirs() }
                val f = File(dir, UUID.randomUUID().toString() + ".txt")
                out = f
                var total = 0L
                val ins = cr.openInputStream(uri) ?: throw IOException("无法打开文件")
                ins.use { input ->
                    f.outputStream().use { os ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            total += n
                            importing.value = "正在导入… ${total / (1024 * 1024)} MB"
                        }
                    }
                }
                if (f.length() <= 0L) throw IOException("文件为空")
                val det = Codec.detect(f)
                val id = db.bookDao().insert(
                    Book(
                        title = name.substringBeforeLast('.'),
                        path = f.absolutePath,
                        size = f.length(),
                        encoding = det.name,
                        dataStart = det.dataStart,
                        lastOffset = det.dataStart.toLong()
                    )
                )
                importing.value = null
                reindex(id)
            } catch (e: CancellationException) {
                out?.delete()
                throw e
            } catch (e: Exception) {
                out?.delete()
                messages.tryEmit("导入失败：${e.message}")
            } finally {
                importing.value = null
            }
        }
    }

    /** (Re)builds the index with the current rules; manually edited chapters are kept. */
    fun reindex(bookId: Long) {
        jobs.remove(bookId)?.cancel()
        jobs[bookId] = scope.launch { runIndex(bookId) }
    }

    /** Switches the encoding, drops the old index (byte offsets change) and rebuilds it. */
    suspend fun changeEncoding(bookId: Long, enc: String) {
        jobs.remove(bookId)?.cancelAndJoin()
        val book = db.bookDao().get(bookId) ?: return
        val ds = withContext(Dispatchers.IO) { Codec.bomLength(File(book.path), enc) }
        db.withTransaction {
            db.chapterDao().deleteAll(bookId)
            db.bookDao().resetEncoding(bookId, enc, ds)
        }
        reindex(bookId)
    }

    fun delete(book: Book) {
        scope.launch {
            jobs.remove(book.id)?.cancelAndJoin()
            db.bookDao().delete(book.id)
            File(book.path).delete()
            clearProgress(book.id)
        }
    }

    private suspend fun runIndex(bookId: Long) {
        val me = currentCoroutineContext()[Job]
        setProgress(bookId, 0)
        try {
            val book = db.bookDao().get(bookId) ?: return
            val rules = try {
                ChapterIndexer.parseRules(prefs.ruleText)
            } catch (e: IllegalArgumentException) {
                messages.tryEmit("识别规则无效，已改用内置规则")
                ChapterIndexer.parseRules("")
            }
            val res = BookFile(book).use { bf ->
                ChapterIndexer.scan(bf, rules) { pct -> setProgress(bookId, pct) }
            }
            if (res.recovered > 0) messages.tryEmit("已自动补全 ${res.recovered} 个漏掉的章节")
            if (res.missing.isNotEmpty()) {
                val shown = res.missing.take(6).joinToString("、")
                val more = if (res.missing.size > 6) " 等共 ${res.missing.size} 章" else ""
                messages.tryEmit("疑似缺失：$shown$more（可在目录里手动添加）")
            }
            db.withTransaction {
                val manualPos = db.chapterDao().all(bookId).filter { it.manual }.map { it.pos }.toSet()
                db.chapterDao().deleteAuto(bookId)
                db.chapterDao().insertAll(
                    res.chapters.filter { it.pos !in manualPos }
                        .map { Chapter(bookId = bookId, title = it.title, pos = it.pos) }
                )
                db.bookDao().setIndexed(bookId, true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messages.tryEmit("索引失败：${e.message}")
        } finally {
            val cur = jobs[bookId]
            if (cur == null || cur === me) {
                jobs.remove(bookId)
                clearProgress(bookId)
            }
        }
    }
}
