package com.yanpeng.netmon

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppConnAdapter(
    private val windowMsProvider: () -> Long = { 60_000L }
) : RecyclerView.Adapter<AppConnAdapter.VH>() {
    private val items = mutableListOf<AppInsight>()

    fun submit(list: List<AppInsight>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val pm = holder.icon.context.packageManager
        val resolved = AppResolver.resolve(pm, item.uid)
        val title = if (!resolved.appName.startsWith("未知应用")) resolved.appName else item.appName
        val pkg = AppResolver.primaryPackage(
            if (!resolved.packageName.startsWith("uid:")) resolved.packageName else item.packageName
        )
        holder.appName.text = title
        val connPart = if (item.connReadable) {
            if (item.connCount > 0) " · ${item.connCount} 连接" else ""
        } else " · 连接不可读"
        holder.packageName.text = "$pkg  ·  uid ${item.uid}$connPart"
        holder.trafficDetail.text = "↓ ${Bytes.fmt(item.rx)}   ↑ ${Bytes.fmt(item.tx)}"
        holder.connCount.text = Bytes.fmt(item.total)
        holder.routeBadge.text = item.routeLabel
        applyRouteStyle(holder.routeBadge, item.route)
        holder.behaviorHint.text = item.behavior
        if (resolved.icon != null) {
            holder.icon.setImageDrawable(resolved.icon)
        } else {
            try {
                holder.icon.setImageDrawable(pm.getApplicationIcon(pkg))
            } catch (_: Exception) {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }
        holder.itemView.setOnClickListener {
            val ctx = holder.itemView.context
            ctx.startActivity(
                Intent(ctx, AppDetailActivity::class.java)
                    .putExtra(AppDetailActivity.EXTRA_UID, item.uid)
                    .putExtra(AppDetailActivity.EXTRA_WINDOW_MS, windowMsProvider())
            )
        }
    }

    override fun getItemCount(): Int = items.size

    private fun applyRouteStyle(tv: TextView, route: RouteMode) {
        when (route) {
            RouteMode.PROXY -> {
                tv.setBackgroundResource(R.drawable.badge_proxy)
                tv.setTextColor(Color.parseColor("#E65100"))
            }
            RouteMode.DIRECT -> {
                tv.setBackgroundResource(R.drawable.badge_direct)
                tv.setTextColor(Color.parseColor("#2E7D32"))
            }
            RouteMode.VPN_APP -> {
                tv.setBackgroundResource(R.drawable.badge_vpn)
                tv.setTextColor(Color.parseColor("#1565C0"))
            }
            RouteMode.UNKNOWN -> {
                tv.setBackgroundResource(R.drawable.badge_bg)
                tv.setTextColor(Color.parseColor("#757575"))
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val appName: TextView = v.findViewById(R.id.appName)
        val packageName: TextView = v.findViewById(R.id.packageName)
        val trafficDetail: TextView = v.findViewById(R.id.trafficDetail)
        val connCount: TextView = v.findViewById(R.id.connCount)
        val routeBadge: TextView = v.findViewById(R.id.routeBadge)
        val behaviorHint: TextView = v.findViewById(R.id.behaviorHint)
    }
}
