package com.example.txtreader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.txtreader.core.Bookmarks
import com.example.txtreader.core.pctText
import com.example.txtreader.databinding.FragmentBookmarksBinding
import kotlinx.coroutines.launch

class BookmarksFragment : Fragment() {
    private var _b: FragmentBookmarksBinding? = null
    private val b get() = _b!!
    private var bookId = 0L
    private var curPos = 0L
    private val adapter = EntryAdapter({ jump(it) }, { confirmDelete(it) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = requireArguments().getLong("bookId")
        curPos = requireArguments().getLong("pos")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentBookmarksBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        b.list.adapter = adapter
        b.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        b.btnAdd.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val bk = app.db.bookDao().get(bookId) ?: return@launch
                val ok = Bookmarks.add(app.db, bk, curPos)
                toast(if (ok) "已添加书签" else "该位置已有书签")
                reload()
            }
        }
        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val size = app.db.bookDao().get(bookId)?.size ?: 1L
            val list = app.db.bookmarkDao().list(bookId).map {
                Entry(it.id, it.pos, "${it.chapterTitle} · ${pctText(it.pos, size)}", it.snippet)
            }
            val v = _b ?: return@launch
            adapter.set(list)
            v.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun jump(e: Entry) {
        parentFragmentManager.setFragmentResult("jump", bundleOf("pos" to e.pos, "hl" to ""))
        parentFragmentManager.popBackStack()
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(requireContext())
            .setMessage("删除这个书签？")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    app.db.bookmarkDao().delete(e.id)
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
        fun newInstance(bookId: Long, pos: Long) = BookmarksFragment().apply {
            arguments = bundleOf("bookId" to bookId, "pos" to pos)
        }
    }
}
