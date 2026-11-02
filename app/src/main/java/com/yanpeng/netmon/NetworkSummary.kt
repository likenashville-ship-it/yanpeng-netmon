package com.yanpeng.netmon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkSummary {

    fun currentType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "无网络"
        val parts = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "Wi‑Fi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "蜂窝"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "有线"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        ) parts += "VPN"
        val tun = findTunInterfaces()
        if (tun.isNotEmpty() && parts.none { it == "VPN" }) parts += "VPN"
        return if (parts.isEmpty()) "未知" else parts.joinToString("+")
    }

    fun build(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lines = mutableListOf<String>()
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val link = active?.let { cm.getLinkProperties(it) }

        lines += "默认网络：${currentType(context)}"

        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val ssid = info?.ssid?.trim('"')
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>" && ssid != "0x") {
                lines += "Wi‑Fi SSID：$ssid"
            }
        } catch (_: Exception) {
        }

        findIpv4()?.let { lines += "本机 IPv4：$it" }

        val tunIfaces = findTunInterfaces()
        lines += if (tunIfaces.isNotEmpty()) {
            "VPN / tun：${tunIfaces.joinToString(", ")}"
        } else {
            "VPN / tun：未检测到"
        }

        val vpnApps = findVpnPackageHints(context)
        if (vpnApps.isNotEmpty()) {
            lines += "疑似 VPN 应用：${vpnApps.joinToString("、")}"
        }

        if (link != null) {
            val dns = link.dnsServers.mapNotNull { it.hostAddress }.take(2)
            if (dns.isNotEmpty()) lines += "DNS：${dns.joinToString(", ")}"
        }

        // silence unused
        caps
        return lines.joinToString("\n")
    }

    private fun findIpv4(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name ?: continue
                if (name.startsWith("tun") || name.startsWith("ppp")) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun findTunInterfaces(): List<String> {
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
                    out += if (ip != null) "$name ($ip)" else name
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun findVpnPackageHints(context: Context): List<String> {
        val hints = mutableListOf<String>()
        val keywords = listOf(
            "clash", "v2ray", "singbox", "shadowsocks", "wireguard",
            "openvpn", "netguard", "surfboard", "sager", "hiddify"
        )
        val common = listOf(
            "com.github.kr328.clash",
            "com.github.kr328.clash.foss",
            "com.github.metacubex.clash.meta",
            "com.github.kr328.clash.meta",
            "com.netguard.security.assistant",
            "com.v2ray.ang",
            "io.nekohasekai.sfa",
            "com.github.shadowsocks",
            "org.wireguard.android"
        )
        try {
            val pm = context.packageManager
            for (pkg in common) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    hints += "${pm.getApplicationLabel(ai)} ($pkg)"
                } catch (_: Exception) {
                }
            }
            val installed: List<ApplicationInfo> = try {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    pm.getInstalledApplications(0)
                }
            } catch (_: Exception) {
                emptyList()
            }
            for (ai in installed) {
                val pkg = ai.packageName.lowercase()
                if (common.any { it.equals(ai.packageName, ignoreCase = true) }) continue
                if (keywords.any { pkg.contains(it) }) {
                    val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { ai.packageName }
                    hints += "$label (${ai.packageName})"
                }
            }
        } catch (_: Exception) {
        }
        return hints.distinct().take(5)
    }
}
