package com.yanpeng.netmon

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "应用联网详情"

        val uid = intent.getIntExtra(EXTRA_UID, -1)
        val windowMs = intent.getLongExtra(EXTRA_WINDOW_MS, 60_000L)
        if (uid < 0) {
            finish()
            return
        }
        load(uid, windowMs)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun load(uid: Int, windowMs: Long) {
        lifecycleScope.launch {
            val ctx = applicationContext
            val vpn = withContext(Dispatchers.IO) { ProxyDetect.vpnStatus(ctx) }
            val traffic = withContext(Dispatchers.IO) {
                TrafficProbe.recent(ctx, windowMs).firstOrNull { it.uid == uid }
            }
            val probe = withContext(Dispatchers.IO) { NetProbe.scan(ctx) }
            val remotes = probe.byUid[uid]?.remotes
                ?: withContext(Dispatchers.IO) { NetProbe.connectionsForUid(uid).remotes }
            val resolved = withContext(Dispatchers.IO) { AppResolver.resolve(packageManager, uid) }
            val pkg = AppResolver.primaryPackage(
                when {
                    traffic != null && !traffic.packageName.startsWith("uid:") -> traffic.packageName
                    else -> resolved.packageName
                }
            )
            val name = when {
                traffic != null && !traffic.appName.startsWith("未知") -> traffic.appName
                else -> resolved.appName
            }
            val route = ProxyDetect.classifyUid(uid, pkg, remotes, vpn)
            val behavior = BehaviorHints.forApp(
                pkg, name, traffic?.rx ?: 0L, traffic?.tx ?: 0L, remotes, route
            )

            findViewById<TextView>(R.id.detailAppName).text = name
            findViewById<TextView>(R.id.detailPackage).text = "$pkg  ·  uid $uid"
            val iconView = findViewById<ImageView>(R.id.detailIcon)
            if (resolved.icon != null) {
                iconView.setImageDrawable(resolved.icon)
            } else {
                try {
                    iconView.setImageDrawable(packageManager.getApplicationIcon(pkg))
                } catch (_: Exception) {
                    iconView.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }

            val routeView = findViewById<TextView>(R.id.detailRoute)
            routeView.text = ProxyDetect.routeLabel(route)
            applyRouteStyle(routeView, route)

            val sec = windowMs / 1000
            val rx = traffic?.rx ?: 0L
            val tx = traffic?.tx ?: 0L
            findViewById<TextView>(R.id.detailTraffic).text =
                "时间窗：最近约 ${sec}s（NetworkStats）\n" +
                    "下行 ↓ ${Bytes.fmt(rx)}    上行 ↑ ${Bytes.fmt(tx)}\n" +
                    "合计 ${Bytes.fmt(rx + tx)}\n" +
                    vpn.label

            findViewById<TextView>(R.id.detailBehavior).text = "行为解读：$behavior"

            val meta = findViewById<TextView>(R.id.detailConnMeta)
            val list = findViewById<LinearLayout>(R.id.detailConnList)
            val empty = findViewById<TextView>(R.id.detailConnEmpty)
            list.removeAllViews()

            if (!probe.readable && remotes.isEmpty()) {
                meta.text = "连接可读性：不可用"
                empty.visibility = View.VISIBLE
                empty.text = probe.error
                    ?: "连接详情需更高权限/当前不可用。仍可根据流量与 VPN 状态判断。"
            } else if (remotes.isEmpty()) {
                meta.text = "当前 ESTABLISHED TCP：0"
                empty.visibility = View.VISIBLE
                empty.text = "本段有或曾有流量，但此刻没有仍在 ESTABLISHED 的 TCP。\n" +
                    "可能原因：连接已结束、主要走 UDP/QUIC，或系统隐藏了部分套接字。"
            } else {
                empty.visibility = View.GONE
                val proxyN = remotes.count {
                    ProxyDetect.isFakeIp(it.ip) || ProxyDetect.isLocalProxy(it)
                }
                meta.text = "当前 ESTABLISHED TCP：${remotes.size}（疑似代理 ${proxyN}）"
                val inflater = LayoutInflater.from(this@AppDetailActivity)
                for (c in remotes.take(40)) {
                    val row = inflater.inflate(R.layout.item_conn, list, false)
                    row.findViewById<TextView>(R.id.connEndpoint).text =
                        "${c.endpoint}  ·  ${c.protocol}"
                    row.findViewById<TextView>(R.id.connHint).text = annotate(c)
                    list.addView(row)
                }
                if (remotes.size > 40) {
                    val more = TextView(this@AppDetailActivity)
                    more.text = "…另有 ${remotes.size - 40} 条未展开"
                    more.setTextColor(ContextCompat.getColor(this@AppDetailActivity, R.color.text_secondary))
                    more.textSize = 12f
                    more.setPadding(8, 8, 8, 8)
                    list.addView(more)
                }
            }
        }
    }

    private fun annotate(c: RemoteConn): String {
        val bits = mutableListOf<String>()
        bits += NetProbe.portHint(c.port)
        when {
            ProxyDetect.isFakeIp(c.ip) -> bits += "Clash 假 IP / 合成地址"
            ProxyDetect.isLoopback(c.ip) && c.port in setOf(7890, 7891, 7897, 1080, 10808, 45107, 9090) ->
                bits += "本机代理口"
            ProxyDetect.isPrivate(c.ip) -> bits += "内网/私网"
            else -> bits += "公网远端"
        }
        return bits.joinToString(" · ")
    }

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

    companion object {
        const val EXTRA_UID = "uid"
        const val EXTRA_WINDOW_MS = "window_ms"
    }
}
