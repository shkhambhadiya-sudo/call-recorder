package com.sanket.callrecorder

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsAdapter(
    private var items: List<File>,
    private val onPlay: (File) -> Unit,
    private val onShare: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val play: ImageButton = v.findViewById(R.id.btnPlay)
        val share: ImageButton = v.findViewById(R.id.btnShare)
        val delete: ImageButton = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        holder.title.text = f.name
        val size = Formatter.formatShortFileSize(holder.itemView.context, f.length())
        holder.subtitle.text = "${dateFmt.format(Date(f.lastModified()))}  •  $size"
        holder.play.setOnClickListener { onPlay(f) }
        holder.share.setOnClickListener { onShare(f) }
        holder.delete.setOnClickListener { onDelete(f) }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<File>) {
        items = newItems
        notifyDataSetChanged()
    }
}
