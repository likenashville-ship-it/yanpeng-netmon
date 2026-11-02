package com.yanpeng.netmon

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.os.Build
import android.util.Range
import java.net.Inet4Address
import java.net.NetworkInterface

enum class RouteMode {
    PROXY,   // 经 Clash / 本机代理
    DIRECT,  // 直连（VPN 开启但未纳入代理 UID）
    VPN_APP, // 本身是 VPN/Clash 进程
    UNKNOWN
}

data class VpnStatus(
    val vpnActive: Boolean,
    val tunNames: List<String>,
    val clashPackages: List<String>,
    val clashUids: Set<Int>,
    /** UIDs explicitly routed into the VPN (from NetworkCapabilities), if readable. */
    val proxiedUidRanges: List<Pair<Int, Int>>,
    val httpProxy: String?,
    val label: String
) {
    fun isUidProxied(uid: Int): Boolean? {
        if (proxiedUidRanges.isEmpty()) return null
        // Also match app clone UIDs (999xxxxxx → base)
        val base = if (uid > 99000000) uid % 100000 else uid
        for ((a, b) in proxiedUidRanges) {
            if (uid in a..b || base in a..b) return true
            // cloned range often stored as 999xxxxx
            if (uid in a..b) return true
        }
        return false
    }
}

object ProxyDetect {

    private val CLASH_PACKAGES = listOf(
        "com.netguard.security.assistant",
        "com.github.kr328.clash",
        "com.github.kr328.clash.foss",
        "com.github.metacubex.clash.meta",
        "com.github.kr328.clash.meta",
        "com.github.kr328.clash.premium",
        "com.v2ray.ang",
        "io.nekohasekai.sfa",
        "com.github.shadowsocks",
        "org.wireguard.android"
    )

    private val LOCAL_PROXY_PORTS = setOf(
        7890, 7891, 7892, 7893, 7897, 1080, 1081, 10808, 10809,
        8080, 8888, 9090, 9091, 6152, 6153, 45107
    )

    fun vpnStatus(context: Context): VpnStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var vpnActive = false
        var proxyStr: String? = null
        val ranges = mutableListOf<Pair<Int, Int>>()

        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                if (!isVpn) continue
                vpnActive = true
                ranges += readUidRanges(caps)
                val lp = cm.getLinkProperties(n)
                proxyStr = proxyStr ?: formatProxy(lp)
            }
        } catch (_: Exception) {
        }

        val tun = findTun()
        if (tun.isNotEmpty()) vpnActive = true

        // Live caps usually strip Uids for third-party apps — merge local cache.
        var rangeSource = if (ranges.isNotEmpty()) "live" else ""
        if (ranges.isEmpty()) {
            val snap = VpnUidStore.load(context)
            if (snap.ranges.isNotEmpty()) {
                ranges += snap.ranges
                rangeSource = "cache:${snap.source}"
            }
        } else {
            // Refresh cache whenever the system actually exposes Uids
            VpnUidStore.save(context, ranges, "live")
        }

        val clash = findClashApps(context)
        val clashUids = clash.map { it.second }.toSet()
        val names = clash.map { it.first }
        val uniq = ranges.distinct()
        val rangeNote = if (uniq.isNotEmpty()) {
            "· 代理名单 ${uniq.size} 段（$rangeSource）"
        } else if (vpnActive) {
            "· 代理名单不可读（系统对三方应用隐藏 UID，需 ADB 同步）"
        } else ""
        val proxyNote = proxyStr?.let { "· HTTP 代理 $it" } ?: ""
        val label = when {
            names.isNotEmpty() && vpnActive ->
                "Clash/VPN 运行中（${names.joinToString("、")}）$rangeNote$proxyNote"
            names.isNotEmpty() -> "已安装 ${names.joinToString("、")}（当前未必接管流量）"
            vpnActive -> "检测到 VPN/tun$rangeNote$proxyNote"
            else -> "未检测到 VPN"
        }
        return VpnStatus(
            vpnActive = vpnActive,
            tunNames = tun,
            clashPackages = names,
            clashUids = clashUids,
            proxiedUidRanges = uniq,
            httpProxy = proxyStr,
            label = label
        )
    }

    fun classifyUid(
        uid: Int,
        packageName: String,
        remotes: List<RemoteConn>,
        vpn: VpnStatus
    ): RouteMode {
        val pkg = packageName.lowercase()
        if (uid in vpn.clashUids || CLASH_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }) {
            return RouteMode.VPN_APP
        }

        // Prefer connection-based signal when available
        if (remotes.isNotEmpty()) {
            var proxyHits = 0
            var directHits = 0
            for (c in remotes) {
                when {
                    isFakeIp(c.ip) || isLocalProxy(c) -> proxyHits++
                    isLoopback(c.ip) -> { }
                    else -> directHits++
                }
            }
            when {
                proxyHits > 0 -> return RouteMode.PROXY
                directHits > 0 && vpn.vpnActive -> {
                    // Still check VPN uid list — remotes may be real IPs under redirect mode
                    val inVpn = vpn.isUidProxied(uid)
                    if (inVpn == true) return RouteMode.PROXY
                    if (inVpn == false) return RouteMode.DIRECT
                    return RouteMode.DIRECT
                }
                directHits > 0 -> return RouteMode.DIRECT
            }
        }

        // No /proc sockets: use VPN per-app UID allowlist from system
        if (vpn.vpnActive) {
            when (vpn.isUidProxied(uid)) {
                true -> return RouteMode.PROXY
                false -> return RouteMode.DIRECT
                null -> return RouteMode.UNKNOWN
            }
        }
        return RouteMode.UNKNOWN
    }

    fun routeLabel(mode: RouteMode): String = when (mode) {
        RouteMode.PROXY -> "经 Clash 代理"
        RouteMode.DIRECT -> "直连"
        RouteMode.VPN_APP -> "Clash / VPN 本体"
        RouteMode.UNKNOWN -> "路由未知"
    }

    fun isFakeIp(ip: String): Boolean {
        if (ip.startsWith("198.18.") || ip.startsWith("198.19.")) return true
        // Device-observed Clash HTTP proxy / divert address
        if (ip == "127.148.156.5") return true
        val parts = ip.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            if (a == 127 && b != 0) return true
        }
        return false
    }

    fun isLocalProxy(c: RemoteConn): Boolean {
        if (isFakeIp(c.ip)) return true
        if (isLoopback(c.ip) || c.ip.startsWith("10.") || c.ip.startsWith("192.168.") ||
            c.ip.startsWith("172.19.")
        ) {
            return c.port in LOCAL_PROXY_PORTS
        }
        return false
    }

    fun isLoopback(ip: String): Boolean =
        ip == "127.0.0.1" || ip == "::1" || ip.startsWith("127.")

    fun isPrivate(ip: String): Boolean {
        if (isLoopback(ip)) return true
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("192.168.")) return true
        if (ip.startsWith("172.")) {
            val b = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return b in 16..31
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun readUidRanges(caps: NetworkCapabilities): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        fun absorb(set: Set<*>?) {
            if (set == null) return
            for (item in set) {
                if (item == null) continue
                try {
                    when (item) {
                        is Range<*> -> {
                            val lower = item.lower
                            val upper = item.upper
                            if (lower is Int && upper is Int) out += lower to upper
                        }
                        else -> {
                            val lo = item.javaClass.getMethod("getLower").invoke(item)
                            val hi = item.javaClass.getMethod("getUpper").invoke(item)
                            if (lo is Int && hi is Int) out += lo to hi
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        // 1) public/system API getUids()
        try {
            val m = NetworkCapabilities::class.java.methods.firstOrNull {
                it.name == "getUids" && it.parameterTypes.isEmpty()
            }
            if (m != null) {
                m.isAccessible = true
                absorb(m.invoke(caps) as? Set<*>)
            }
        } catch (_: Exception) {
        }
        if (out.isNotEmpty()) return out.distinct()
        // 2) hidden field mUids / mUidsUnmodifiable
        for (name in listOf("mUids", "mUidsUnmodifiable", "uids")) {
            try {
                var cls: Class<*>? = NetworkCapabilities::class.java
                var field: java.lang.reflect.Field? = null
                while (cls != null && field == null) {
                    try { field = cls.getDeclaredField(name) } catch (_: Exception) { cls = cls.superclass }
                }
                if (field != null) {
                    field.isAccessible = true
                    absorb(field.get(caps) as? Set<*>)
                }
            } catch (_: Exception) {
            }
            if (out.isNotEmpty()) return out.distinct()
        }
        // 3) parse NetworkCapabilities.toString() — OEM dumps often include Uids: <{a-b, ...}>
        try {
            val s = caps.toString()
            val m = Regex("""Uids:\s*<\{([^}]*)\}>""").find(s)
                ?: Regex("""uids=\[([^\]]*)\]""", RegexOption.IGNORE_CASE).find(s)
            if (m != null) {
                val body = m.groupValues[1]
                for (part in body.split(',')) {
                    val t = part.trim()
                    if (t.isEmpty()) continue
                    val r = Regex("""(\d+)\s*-\s*(\d+)""").find(t)
                    if (r != null) {
                        out += r.groupValues[1].toInt() to r.groupValues[2].toInt()
                    } else {
                        val one = t.toIntOrNull()
                        if (one != null) out += one to one
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.distinct()
    }

    private fun formatProxy(lp: LinkProperties?): String? {
        if (lp == null) return null
        return try {
            val p: ProxyInfo? = lp.httpProxy
            if (p == null || p.host.isNullOrBlank()) null
            else "${p.host}:${p.port}"
        } catch (_: Exception) {
            null
        }
    }

    private fun findTun(): List<String> {
        val out = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (iface in ifaces) {
                if (!iface.isUp) continue
                val name = iface.name ?: continue
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("wg")) {
                    val ip = iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull()?.hostAddress
                    out += if (ip != null) "$name($ip)" else name
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun findClashApps(context: Context): List<Pair<String, Int>> {
        val pm = context.packageManager
        val found = mutableListOf<Pair<String, Int>>()
        for (pkg in CLASH_PACKAGES) {
            try {
                val ai = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                val label = try {
                    pm.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    pkg
                }
                found += label to ai.uid
            } catch (_: Exception) {
            }
        }
        return found
    }
}
