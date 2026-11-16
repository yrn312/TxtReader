package com.example.txtreader.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.txtreader.data.Chapter
import com.example.txtreader.databinding.FragmentTocBinding
import com.example.txtreader.databinding.ItemChapterBinding
import kotlinx.coroutines.launch

class TocFragment : Fragment() {
    private var _b: FragmentTocBinding? = null
    private val b get() = _b!!
    private var bookId = 0L
    private var curPos = 0L
    private var all: List<Chapter> = emptyList()
    private var reversed = false
    private var firstLoad = true
    private val adapter = ChapterAdapter({ jump(it) }, { manage(it) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = requireArguments().getLong("bookId")
        curPos = requireArguments().getLong("pos")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentTocBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        b.btnOrder.setOnClickListener {
            reversed = !reversed
            b.btnOrder.text = if (reversed) "正序" else "倒序"
            refresh(false)
        }
        b.etSearch.doAfterTextChanged { refresh(false) }
        b.btnAdd.setOnClickListener { addHere() }
        b.btnRules.setOnClickListener { RuleDialog.show(this, bookId) }
        b.btnReindex.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage("按当前识别规则重建章节索引？手动编辑过的章节会保留。")
                .setPositiveButton("重建") { _, _ -> app.index.reindex(bookId) }
                .setNegativeButton("取消", null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var was = false
                app.index.progress.collect { m ->
                    val p = m[bookId]
                    if (p != null) {
                        was = true
                        b.tvBanner.visibility = View.VISIBLE
                        b.tvBanner.text = "索引进行中 $p% … 完成后自动刷新"
                    } else {
                        b.tvBanner.visibility = View.GONE
                        if (was) {
                            was = false
                            reload()
                        }
                    }
                }
            }
        }
        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            all = app.db.chapterDao().all(bookId)
            refresh(firstLoad)
            firstLoad = false
        }
    }

    private fun refresh(scrollToCurrent: Boolean) {
        val v = _b ?: return
        val q = v.etSearch.text.toString().trim()
        var list = if (q.isEmpty()) all else all.filter { it.title.contains(q, ignoreCase = true) }
        if (reversed) list = list.asReversed()
        val cur = all.lastOrNull { it.pos <= curPos }
        adapter.submit(list, cur?.id ?: -1L)
        v.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (scrollToCurrent && cur != null) {
            val idx = list.indexOfFirst { it.id == cur.id }
            if (idx >= 0) (v.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 200)
        }
    }

    private fun jump(c: Chapter) {
        parentFragmentManager.setFragmentResult("jump", bundleOf("pos" to c.pos, "hl" to ""))
        parentFragmentManager.popBackStack()
    }

    private fun manage(c: Chapter) {
        AlertDialog.Builder(requireContext())
            .setTitle(c.title)
            .setItems(arrayOf("重命名", "删除")) { _, which ->
                if (which == 0) rename(c) else confirmDelete(c)
            }
            .show()
    }

    private fun rename(c: Chapter) {
        val et = EditText(requireContext()).apply { setText(c.title); setSelectAllOnFocus(true) }
        AlertDialog.Builder(requireContext())
            .setTitle("重命名章节")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        app.db.chapterDao().rename(c.id, t)
                        reload()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(c: Chapter) {
        AlertDialog.Builder(requireContext())
            .setMessage("删除章节“${c.title}”？（只删除目录项，不影响正文）")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    app.db.chapterDao().delete(c.id)
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addHere() {
        val et = EditText(requireContext()).apply { setText("新章节"); setSelectAllOnFocus(true) }
        AlertDialog.Builder(requireContext())
            .setTitle("在当前阅读位置添加章节")
            .setView(et)
            .setPositiveButton("添加") { _, _ ->
                val t = et.text.toString().trim().ifEmpty { "新章节" }
                viewLifecycleOwner.lifecycleScope.launch {
                    if (all.any { it.pos == curPos }) {
                        toast("该位置已有章节")
                        return@launch
                    }
                    app.db.chapterDao().insert(Chapter(bookId = bookId, title = t, pos = curPos, manual = true))
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        fun newInstance(bookId: Long, pos: Long) = TocFragment().apply {
            arguments = bundleOf("bookId" to bookId, "pos" to pos)
        }
    }
}

class ChapterAdapter(
    private val onClick: (Chapter) -> Unit,
    private val onLong: (Chapter) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.VH>() {
    private var items: List<Chapter> = emptyList()
    private var currentId = -1L

    class VH(val b: ItemChapterBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<Chapter>, curId: Long) {
        items = list
        currentId = curId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val cur = c.id == currentId
        holder.b.tvTitle.text = c.title
        holder.b.tvTitle.setTextColor(if (cur) 0xFF1976D2.toInt() else 0xFF222222.toInt())
        holder.b.tvTitle.typeface = if (cur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        holder.b.tvTitle.setBackgroundColor(if (cur) 0x1A1976D2 else Color.TRANSPARENT)
        holder.b.tvTitle.setOnClickListener { onClick(c) }
        holder.b.tvTitle.setOnLongClickListener { onLong(c); true }
    }
}
