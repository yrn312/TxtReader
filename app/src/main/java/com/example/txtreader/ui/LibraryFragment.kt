package com.example.txtreader.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.txtreader.core.Codec
import com.example.txtreader.core.pctText
import com.example.txtreader.data.Book
import com.example.txtreader.data.BookItem
import com.example.txtreader.databinding.FragmentLibraryBinding
import com.example.txtreader.databinding.ItemBookBinding
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {
    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!
    private var books: List<BookItem> = emptyList()
    private var prog: Map<Long, Int> = emptyMap()
    private lateinit var adapter: BookAdapter

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.index.import(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = BookAdapter(
            onClick = { bi ->
                (requireActivity() as MainActivity).push(ReaderFragment.newInstance(bi.book.id))
            },
            onLong = { bi -> manage(bi.book) }
        )
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        b.list.adapter = adapter
        b.btnImport.setOnClickListener { picker.launch(arrayOf("*/*")) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.db.bookDao().observeAll().collect {
                        books = it
                        render()
                    }
                }
                launch {
                    app.index.progress.collect {
                        prog = it
                        render()
                    }
                }
                launch {
                    app.index.importing.collect { s ->
                        b.tvImport.visibility = if (s == null) View.GONE else View.VISIBLE
                        b.tvImport.text = s ?: ""
                    }
                }
                launch { app.index.messages.collect { toast(it) } }
            }
        }
    }

    private fun render() {
        adapter.submit(books, prog)
        b.tvEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun manage(book: Book) {
        val opts = arrayOf("切换编码并重建索引", "重新建立索引", "删除")
        AlertDialog.Builder(requireContext())
            .setTitle(book.title)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> chooseEncoding(book)
                    1 -> {
                        app.index.reindex(book.id)
                        toast("已开始重建索引")
                    }
                    2 -> confirmDelete(book)
                }
            }
            .show()
    }

    private fun chooseEncoding(book: Book) {
        val cur = Codec.ENCODINGS.indexOf(book.encoding)
        var sel = cur
        AlertDialog.Builder(requireContext())
            .setTitle("选择编码（将重建索引）")
            .setSingleChoiceItems(Codec.ENCODINGS.toTypedArray(), cur) { _, w -> sel = w }
            .setPositiveButton("确定") { _, _ ->
                if (sel >= 0) {
                    val enc = Codec.ENCODINGS[sel]
                    if (enc != book.encoding) {
                        viewLifecycleOwner.lifecycleScope.launch { app.index.changeEncoding(book.id, enc) }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(book: Book) {
        AlertDialog.Builder(requireContext())
            .setMessage("删除《${book.title}》及其章节、书签？")
            .setPositiveButton("删除") { _, _ -> app.index.delete(book) }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

class BookAdapter(
    private val onClick: (BookItem) -> Unit,
    private val onLong: (BookItem) -> Unit
) : RecyclerView.Adapter<BookAdapter.VH>() {
    private var items: List<BookItem> = emptyList()
    private var prog: Map<Long, Int> = emptyMap()

    class VH(val b: ItemBookBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(i: List<BookItem>, p: Map<Long, Int>) {
        items = i
        prog = p
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val bi = items[position]
        val bk = bi.book
        val p = prog[bk.id]
        val state = when {
            p != null -> "索引中 $p%"
            !bk.indexed -> "等待索引"
            else -> "${bi.chapterCount} 章"
        }
        val ctx = holder.b.root.context
        holder.b.tvTitle.text = bk.title
        holder.b.tvInfo.text = "${Formatter.formatFileSize(ctx, bk.size)} · ${bk.encoding} · $state"
        holder.b.tvProgress.text =
            if (bk.lastReadAt > 0) "已读 ${pctText(bk.lastOffset, bk.size)}" else "未开始阅读"
        holder.b.root.setOnClickListener { onClick(bi) }
        holder.b.root.setOnLongClickListener { onLong(bi); true }
    }
}
