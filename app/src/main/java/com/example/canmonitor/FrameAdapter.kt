package com.example.canmonitor

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.canmonitor.databinding.RowFrameBinding

class FrameAdapter : RecyclerView.Adapter<FrameAdapter.VH>() {

    private val rows = mutableListOf<Triple<String, String, String>>()  // zaman | id | veri

    class VH(val b: RowFrameBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(RowFrameBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (t, id, data) = rows[position]
        holder.b.tvTime.text = t
        holder.b.tvId.text = id
        holder.b.tvData.text = data
    }

    override fun getItemCount() = rows.size

    @SuppressLint("NotifyDataSetChanged")
    fun submit(frames: List<CanFrame>) {
        rows.clear()
        frames.forEach {
            rows += Triple(String.format("%.3f", it.elapsedMs / 1000.0), it.id, it.dataHex)
        }
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setLines(lines: List<String>) {
        rows.clear()
        lines.forEach { rows += Triple("", "", it) }
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clear() {
        rows.clear()
        notifyDataSetChanged()
    }
}
