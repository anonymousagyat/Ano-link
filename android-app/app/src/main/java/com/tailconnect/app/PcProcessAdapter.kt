package com.tailconnect.app

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PcProcessAdapter(
    private var processList: MutableList<PcProcessItem>,
    private val onItemClick: ((item: PcProcessItem, cardView: View, position: Int) -> Unit)? = null,
    private val onEndTaskClick: (item: PcProcessItem, cardView: View, position: Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_ITEM = 1

        val iconCache = LruCache<String, Bitmap>(128)

        fun getVectorFallback(exeName: String): Int {
            val lower = exeName.lowercase()
            return when {
                lower.startsWith("explorer") -> R.drawable.ic_proc_explorer
                lower.startsWith("cmd") || lower == "wt.exe" || lower.startsWith("powershell") || lower.startsWith("pwsh") -> R.drawable.ic_proc_cmd
                lower.startsWith("brave") -> R.drawable.ic_proc_brave
                lower.startsWith("chrome") -> R.drawable.ic_proc_chrome
                lower.startsWith("code") -> R.drawable.ic_proc_vscode
                lower.startsWith("antigravity") || lower.startsWith("agy") -> R.drawable.ic_proc_antigravity
                lower.startsWith("spotify") -> R.drawable.ic_proc_spotify
                lower.startsWith("steam") -> R.drawable.ic_proc_steam
                lower.startsWith("discord") -> R.drawable.ic_proc_discord
                lower.startsWith("taskmgr") -> R.drawable.ic_proc_taskmgr
                lower.startsWith("node") -> R.drawable.ic_proc_node
                else -> R.drawable.ic_proc_default_app
            }
        }
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvSectionTitle)
    }

    class ProcessViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rootCard: LinearLayout = view.findViewById(R.id.llProcCardRoot)
        val ivIcon: ImageView = view.findViewById(R.id.ivProcIcon)
        val tvName: TextView = view.findViewById(R.id.tvProcName)
        val tvPid: TextView = view.findViewById(R.id.tvProcPid)
        val tvCpu: TextView = view.findViewById(R.id.tvProcCpu)
        val pbCpu: ProgressBar = view.findViewById(R.id.pbProcCpu)
        val tvMem: TextView = view.findViewById(R.id.tvProcMem)
        val pbMem: ProgressBar = view.findViewById(R.id.pbProcMem)
        val btnEnd: LinearLayout = view.findViewById(R.id.btnEndTask)
    }

    override fun getItemViewType(position: Int): Int {
        return if (processList[position].isHeader) VIEW_TYPE_HEADER else VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_proc_section_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pc_process, parent, false)
            ProcessViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = processList[position]

        if (holder is HeaderViewHolder) {
            holder.tvTitle.text = item.headerTitle
            // Warm Amber for APPS, Muted Slate for BACKGROUND PROCESSES — ZERO CYAN!
            holder.tvTitle.setTextColor(
                if (item.isAppSection) Color.parseColor("#F59E0B") else Color.parseColor("#94A3B8")
            )
            return
        }

        if (holder is ProcessViewHolder) {
            holder.tvName.text = item.name
            holder.tvPid.text = "PID ${item.pid}"

            // Warm Amber CPU Telemetry (NO CYAN)
            holder.tvCpu.text = String.format("%.1f%% CPU", item.cpu)
            holder.pbCpu.progress = (item.cpu * 3.5f).toInt().coerceIn(0, 100)

            // Lavender RAM Telemetry
            if (item.mem >= 1000f) {
                holder.tvMem.text = String.format("%.1f GB", item.mem / 1024f)
            } else {
                holder.tvMem.text = String.format("%.0f MB", item.mem)
            }
            holder.pbMem.progress = item.mem.toInt().coerceIn(0, 1000)

            // High-resolution logo or crisp vector fallback
            val iconKey = item.exe.lowercase()
            val bmp = item.iconBitmap ?: iconCache.get(iconKey)
            if (bmp != null) {
                holder.ivIcon.setImageBitmap(bmp)
            } else {
                holder.ivIcon.setImageResource(getVectorFallback(iconKey))
            }

            // Tap or Long-Press Card to open Process Inspector
            holder.rootCard.setOnClickListener {
                onItemClick?.invoke(item, holder.rootCard, holder.adapterPosition)
            }
            holder.rootCard.setOnLongClickListener {
                onItemClick?.invoke(item, holder.rootCard, holder.adapterPosition)
                true
            }

            // Instant One-Tap End Task
            holder.btnEnd.setOnClickListener {
                onEndTaskClick(item, holder.rootCard, holder.adapterPosition)
            }
        }
    }

    override fun getItemCount(): Int = processList.size

    fun updateList(newList: List<PcProcessItem>) {
        processList.clear()
        processList.addAll(newList)
        notifyDataSetChanged()
    }

    fun removeItemAt(position: Int) {
        if (position in 0 until processList.size) {
            processList.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}
