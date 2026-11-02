package com.yanpeng.netmon

data class AppInsight(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val rx: Long,
    val tx: Long,
    val windowMs: Long,
    val route: RouteMode,
    val routeLabel: String,
    val behavior: String,
    val connCount: Int,
    val remotes: List<RemoteConn>,
    val connReadable: Boolean,
    val connNote: String?
) {
    val total: Long get() = rx + tx
}

object InsightBuilder {

    fun build(
        context: android.content.Context,
        traffic: List<TrafficRow>,
        windowMs: Long,
        probe: ProbeResult,
        vpn: VpnStatus
    ): List<AppInsight> {
        val byUidTraffic = traffic.associateBy { it.uid }
        val uids = (byUidTraffic.keys + probe.byUid.keys).toSet()
        val pm = context.packageManager

        return uids.map { uid ->
            val t = byUidTraffic[uid]
            val c = probe.byUid[uid]
            val resolved = AppResolver.resolve(pm, uid)
            val pkg = AppResolver.primaryPackage(
                when {
                    t != null && !t.packageName.startsWith("uid:") -> t.packageName
                    !resolved.packageName.startsWith("uid:") -> resolved.packageName
                    else -> resolved.packageName
                }
            )
            val name = when {
                t != null && !t.appName.startsWith("未知") -> t.appName
                !resolved.appName.startsWith("未知") -> resolved.appName
                else -> resolved.appName
            }
            val remotes = c?.remotes.orEmpty()
            val route = ProxyDetect.classifyUid(uid, pkg, remotes, vpn)
            val behavior = BehaviorHints.forApp(pkg, name, t?.rx ?: 0L, t?.tx ?: 0L, remotes, route)
            val note = when {
                !probe.readable -> probe.error ?: "连接详情需更高权限/当前不可用"
                remotes.isEmpty() && (t?.total ?: 0) > 0 ->
                    "本段有流量，但当前无 ESTABLISHED TCP（可能已结束或 UDP/QUIC）"
                else -> null
            }
            AppInsight(
                uid = uid,
                packageName = pkg,
                appName = name,
                rx = t?.rx ?: 0L,
                tx = t?.tx ?: 0L,
                windowMs = windowMs,
                route = route,
                routeLabel = ProxyDetect.routeLabel(route),
                behavior = behavior,
                connCount = remotes.size,
                remotes = remotes,
                connReadable = probe.readable,
                connNote = note
            )
        }.sortedByDescending { it.total }
    }

    fun overviewLine(insights: List<AppInsight>, vpn: VpnStatus): String {
        val proxy = insights.count { it.route == RouteMode.PROXY }
        val direct = insights.count { it.route == RouteMode.DIRECT }
        val vpnApp = insights.count { it.route == RouteMode.VPN_APP }
        val top = insights.take(3).joinToString("、") {
            "${it.appName} ${Bytes.fmt(it.total)}"
        }
        val lines = mutableListOf<String>()
        lines += vpn.label
        if (vpn.tunNames.isNotEmpty()) lines += "tun：${vpn.tunNames.joinToString(", ")}"
        lines += "路由摘要：经代理 $proxy · 直连 $direct · 代理本体 $vpnApp"
        if (top.isNotBlank()) lines += "流量 Top：${top}"
        return lines.joinToString("\n")
    }
}
