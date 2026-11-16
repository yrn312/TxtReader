package com.example.txtreader.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.txtreader.core.BookFile
import com.example.txtreader.core.TextSearcher
import com.example.txtreader.core.pctText
import com.example.txtreader.databinding.FragmentSearchBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {
    private var _b: FragmentSearchBinding? = null
    private val b get() = _b!!
    private var bookId = 0L
    private var query = ""
    private var requests: Channel<Unit>? = null
    private var worker: Job? = null
    private var done = true
    private val adapter = EntryAdapter({ jump(it) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = requireArguments().getLong("bookId")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSearchBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val lm = LinearLayoutManager(requireContext())
        b.list.layoutManager = lm
        b.list.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        b.list.adapter = adapter
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!done && lm.findLastVisibleItemPosition() >= adapter.itemCount - 5) {
                    requests?.trySend(Unit)
                }
            }
        })
        b.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        b.btnGo.setOnClickListener { startSearch() }
        b.etQuery.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
        }
        b.etQuery.requestFocus()
    }

    private fun startSearch() {
        val q = b.etQuery.text.toString().replace("\n", "").trim()
        if (q.isEmpty()) {
            toast("请输入搜索内容")
            return
        }
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(b.etQuery.windowToken, 0)
        query = q
        worker?.cancel()
        adapter.set(emptyList())
        b.tvStatus.text = "搜索中…"
        done = false
        val ch = Channel<Unit>(Channel.CONFLATED)
        requests = ch
        worker = viewLifecycleOwner.lifecycleScope.launch {
            val bk = app.db.bookDao().get(bookId) ?: return@launch
            val bf = BookFile(bk)
            try {
                val searcher = TextSearcher(bf, q)
                var total = 0
                ch.trySend(Unit)
                for (req in ch) {
                    _b?.tvStatus?.text = "搜索中…（已找到 $total 条）"
                    val hits = searcher.next(30)
                    val entries = hits.map { h ->
                        val title = app.db.chapterDao().at(bookId, h.pos)?.title ?: bk.title
                        val sp = SpannableString(h.snippet)
                        sp.setSpan(ForegroundColorSpan(0xFF1976D2.toInt()), h.matchStart,
                            minOf(h.snippet.length, h.matchStart + h.matchLen), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sp.setSpan(StyleSpan(Typeface.BOLD), h.matchStart,
                            minOf(h.snippet.length, h.matchStart + h.matchLen), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        Entry(h.pos, h.pos, "$title · ${pctText(h.pos, bk.size)}", sp)
                    }
                    if (_b == null) return@launch
                    adapter.add(entries)
                    total += entries.size
                    if (searcher.finished) {
                        done = true
                        b.tvStatus.text = if (total == 0) "未找到“$q”" else "共 $total 条，已搜索完整本书"
                        break
                    }
                    b.tvStatus.text = "已找到 $total 条，滚动到底部继续加载"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _b?.tvStatus?.text = "搜索失败：${e.message}"
            } finally {
                bf.close()
            }
        }
    }

    private fun jump(e: Entry) {
        parentFragmentManager.setFragmentResult("jump", bundleOf("pos" to e.pos, "hl" to query))
        parentFragmentManager.popBackStack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        worker?.cancel()
        requests = null
        _b = null
    }

    companion object {
        fun newInstance(bookId: Long) = SearchFragment().apply {
            arguments = bundleOf("bookId" to bookId)
        }
    }
}
