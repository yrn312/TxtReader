package com.example.txtreader.ui

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.txtreader.App
import com.example.txtreader.core.BookFile
import com.example.txtreader.core.ChapterIndexer
import com.example.txtreader.core.pctText
import com.example.txtreader.databinding.DialogRulesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Chapter rule editor with live preview over the first 4MB of the book. */
object RuleDialog {
    fun show(f: Fragment, bookId: Long) {
        val app = f.app
        val vb = DialogRulesBinding.inflate(f.layoutInflater)
        vb.etRules.setText(app.prefs.ruleText)
        val dlg = AlertDialog.Builder(f.requireContext())
            .setTitle("章节识别规则")
            .setView(vb.root)
            .setNegativeButton("关闭", null)
            .create()

        vb.btnPreview.setOnClickListener {
            val rules = try {
                ChapterIndexer.parseRules(vb.etRules.text.toString())
            } catch (e: IllegalArgumentException) {
                vb.tvPreview.text = e.message
                return@setOnClickListener
            }
            vb.tvPreview.text = "扫描中…"
            f.viewLifecycleOwner.lifecycleScope.launch {
                val text = try {
                    withContext(Dispatchers.IO) { preview(app, bookId, rules) }
                } catch (e: Exception) {
                    "预览失败：${e.message}"
                }
                vb.tvPreview.text = text
            }
        }
        vb.btnDefault.setOnClickListener {
            vb.etRules.setText("")
            vb.tvPreview.text = "已清空，将使用内置规则（点“应用”生效）"
        }
        vb.btnApply.setOnClickListener {
            val txt = vb.etRules.text.toString()
            try {
                ChapterIndexer.parseRules(txt)
            } catch (e: IllegalArgumentException) {
                vb.tvPreview.text = e.message
                return@setOnClickListener
            }
            app.prefs.ruleText = txt
            app.index.reindex(bookId)
            f.toast("已开始重建索引")
            dlg.dismiss()
        }
        dlg.show()
    }

    private suspend fun preview(app: App, bookId: Long, rules: ChapterIndexer.Rules): String {
        val book = app.db.bookDao().get(bookId) ?: return "找不到书籍"
        return BookFile(book).use { bf ->
            val limit = 4L * 1024 * 1024
            val res = ChapterIndexer.scan(bf, rules, limit)
            val sb = StringBuilder()
            if (res.auto) {
                sb.append("前 4MB 未识别到章节，将按字数自动分节（约 8000 字/节）。\n")
            } else {
                sb.append("前 4MB 识别到 ${res.chapters.size} 个章节：\n")
            }
            res.chapters.take(80).forEachIndexed { i, c ->
                sb.append("${i + 1}. ${c.title}  [${pctText(c.pos, bf.size)}]\n")
            }
            if (res.chapters.size > 80) sb.append("…")
            sb.toString()
        }
    }
}
