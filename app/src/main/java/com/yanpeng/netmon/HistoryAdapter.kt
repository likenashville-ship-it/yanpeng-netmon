package com.yanpeng.netmon

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yanpeng.netmon.db.AppAgg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {
    private val items = mutableListOf<AppAgg>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun submit(list: List<AppAgg>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val pm = holder.itemView.context.packageManager
        // Always re-resolve so old "UID xxx" rows get proper names after upgrade
        val resolved = AppResolver.resolve(pm, item.uid)
        val title = when {
            !resolved.appName.startsWith("未知应用") && !resolved.appName.startsWith("UID") -> resolved.appName
            item.appName.isNotBlank() && !item.appName.startsWith("UID") && item.appName != "uid:${item.uid}" -> item.appName
            else -> resolved.appName
        }
        val pkgShow = when {
            resolved.packageName.isNotBlank() && !resolved.packageName.startsWith("uid:") ->
                AppResolver.primaryPackage(resolved.packageName)
            item.packageName.isNotBlank() && !item.packageName.startsWith("uid:") -> item.packageName
            else -> resolved.packageName
        }
        holder.name.text = title
        holder.pkg.text = "$pkgShow  ·  uid ${item.uid}"
        holder.stats.text =
            "出现 ${item.hitCount} 次 · 合计 ${Bytes.fmt(item.totalBytes)}（↓${Bytes.fmt(item.totalRx)} ↑${Bytes.fmt(item.totalTx)}）· 最近 ${fmt.format(Date(item.lastTs))}"
        if (resolved.icon != null) {
            holder.icon.setImageDrawable(resolved.icon)
        } else {
            try {
                holder.icon.setImageDrawable(pm.getApplicationIcon(pkgShow))
            } catch (_: Exception) {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.hIcon)
        val name: TextView = v.findViewById(R.id.hAppName)
        val pkg: TextView = v.findViewById(R.id.hPackage)
        val stats: TextView = v.findViewById(R.id.hStats)
    }
}
