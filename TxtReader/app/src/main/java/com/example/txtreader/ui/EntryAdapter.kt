package com.example.txtreader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.txtreader.databinding.ItemEntryBinding

class Entry(val id: Long, val pos: Long, val top: String, val body: CharSequence)

/** Shared list adapter for bookmarks and search results. */
class EntryAdapter(
    private val onClick: (Entry) -> Unit,
    private val onLong: ((Entry) -> Unit)? = null
) : RecyclerView.Adapter<EntryAdapter.VH>() {
    private val items = ArrayList<Entry>()

    class VH(val b: ItemEntryBinding) : RecyclerView.ViewHolder(b.root)

    fun set(list: List<Entry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(list: List<Entry>) {
        val s = items.size
        items.addAll(list)
        notifyItemRangeInserted(s, list.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.b.tvTop.text = e.top
        holder.b.tvSnippet.text = e.body
        holder.b.root.setOnClickListener { onClick(e) }
        val lg = onLong
        if (lg != null) {
            holder.b.root.setOnLongClickListener { lg(e); true }
        } else {
            holder.b.root.setOnLongClickListener(null)
            holder.b.root.isLongClickable = false
        }
    }
}
