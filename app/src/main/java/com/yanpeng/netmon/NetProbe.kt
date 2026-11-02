package com.yanpeng.netmon

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.Locale

data class AppConn(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val tcpCount: Int
)

data class RemoteConn(
    val ip: String,
    val port: Int,
    val protocol: String, // TCP / TCP6
    val state: String
) {
    val endpoint: String get() = "$ip:$port"
}

data class UidConns(
    val uid: Int,
    val remotes: List<RemoteConn>,
    val established: Int
)

data class ProbeResult(
    val apps: List<AppConn>,
    val totalTcp: Int,
    val readable: Boolean,
    val error: String?,
    val byUid: Map<Int, UidConns> = emptyMap()
)

object NetProbe {
    private val PROC_FILES = listOf(
        "/proc/net/tcp" to "TCP",
        "/proc/net/tcp6" to "TCP6"
    )

    fun scan(context: Context): ProbeResult {
        val byUidRemotes = HashMap<Int, MutableList<RemoteConn>>()
        var readable = false
        var lastError: String? = null

        for ((path, proto) in PROC_FILES) {
            try {
                val file = File(path)
                if (!file.exists()) {
                    lastError = "$path 不存在"
                    continue
                }
                BufferedReader(FileReader(file)).use { br ->
                    br.readLine() // header
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val parts = line!!.trim().split(Regex("\\s+"))
                        // sl local rem st ... uid ...
                        if (parts.size < 8) continue
                        val st = parts[3]
                        if (st != "01") continue // ESTABLISHED only
                        val uid = parts[7].toIntOrNull() ?: continue
                        val rem = parseRemote(parts[2], proto == "TCP6") ?: continue
                        byUidRemotes.getOrPut(uid) { mutableListOf() }.add(
                            RemoteConn(rem.first, rem.second, proto, "ESTABLISHED")
                        )
                        readable = true
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }

        if (!readable && byUidRemotes.isEmpty()) {
            val anyReadable = PROC_FILES.any { File(it.first).canRead() }
            if (anyReadable) {
                return ProbeResult(emptyList(), 0, true, null, emptyMap())
            }
            return ProbeResult(
                emptyList(), 0, false,
                lastError ?: "系统限制，无法读取 /proc/net/tcp（连接详情不可用）",
                emptyMap()
            )
        }

        // Dedup identical endpoints per uid
        val byUid = byUidRemotes.mapValues { (uid, list) ->
            val dedup = list.distinctBy { "${it.protocol}|${it.endpoint}" }
            UidConns(uid, dedup.sortedBy { it.endpoint }, dedup.size)
        }

        val pm = context.packageManager
        val apps = byUid.values
            .map { resolveUid(pm, it.uid, it.established) }
            .sortedByDescending { it.tcpCount }

        return ProbeResult(apps, byUid.values.sumOf { it.established }, true, null, byUid)
    }

    fun connectionsForUid(uid: Int): UidConns {
        val remotes = mutableListOf<RemoteConn>()
        var readable = false
        for ((path, proto) in PROC_FILES) {
            try {
                val file = File(path)
                if (!file.canRead()) continue
                BufferedReader(FileReader(file)).use { br ->
                    br.readLine()
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val parts = line!!.trim().split(Regex("\\s+"))
                        if (parts.size < 8) continue
                        if (parts[3] != "01") continue
                        val u = parts[7].toIntOrNull() ?: continue
                        if (u != uid) continue
                        val rem = parseRemote(parts[2], proto == "TCP6") ?: continue
                        remotes += RemoteConn(rem.first, rem.second, proto, "ESTABLISHED")
                        readable = true
                    }
                }
            } catch (_: Exception) {
            }
        }
        val dedup = remotes.distinctBy { "${it.protocol}|${it.endpoint}" }
            .sortedWith(compareBy({ it.port }, { it.ip }))
        return UidConns(uid, dedup, dedup.size).also {
            if (!readable) { /* empty ok */ }
        }
    }

    fun resolveUid(pm: PackageManager, uid: Int, count: Int): AppConn {
        val r = AppResolver.resolve(pm, uid)
        return AppConn(uid, r.packageName, r.appName, r.icon, count)
    }

    fun primaryPackage(packageName: String): String = AppResolver.primaryPackage(packageName)

    fun portHint(port: Int): String = when (port) {
        443 -> "HTTPS"
        80 -> "HTTP"
        8080, 8443 -> "HTTP/备用口"
        53 -> "DNS"
        853 -> "DoT"
        5222, 5223 -> "XMPP/推送"
        5228, 5229, 5230 -> "Google 推送"
        14000 -> "QQ 长连"
        16000, 4431 -> "QQ/腾讯备用"
        8081 -> "HTTP 备用"
        3478, 3479 -> "STUN/TURN"
        1935 -> "RTMP"
        554 -> "RTSP"
        21 -> "FTP"
        22 -> "SSH"
        25, 465, 587 -> "邮件"
        993, 995 -> "邮件收信"
        7890, 7891, 7897, 1080, 10808 -> "本地代理口"
        9090, 9091 -> "Clash 控制口"
        45107 -> "本机代理口"
        else -> when {
            port in 14000..14010 -> "QQ 长连"
            port in 8000..8999 -> "应用服务口"
            port >= 49152 -> "动态/临时口"
            else -> "端口 $port"
        }
    }

    /** Parse "IPHEX:PORTHEx" from /proc/net/tcp{,6}. */
    private fun parseRemote(field: String, ipv6: Boolean): Pair<String, Int>? {
        val idx = field.lastIndexOf(':')
        if (idx <= 0) return null
        val ipHex = field.substring(0, idx)
        val portHex = field.substring(idx + 1)
        val port = portHex.toIntOrNull(16) ?: return null
        if (port <= 0) return null
        val ip = if (ipv6) decodeIpv6(ipHex) else decodeIpv4(ipHex)
        return ip?.let { it to port }
    }

    private fun decodeIpv4(hex: String): String? {
        if (hex.length != 8) return null
        return try {
            val v = hex.toLong(16)
            // little-endian host order in /proc
            val b0 = (v and 0xff).toInt()
            val b1 = ((v shr 8) and 0xff).toInt()
            val b2 = ((v shr 16) and 0xff).toInt()
            val b3 = ((v shr 24) and 0xff).toInt()
            "$b0.$b1.$b2.$b3"
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeIpv6(hex: String): String? {
        // 32 hex chars, 4-byte little-endian words
        if (hex.length != 32) {
            // sometimes mapped IPv4 :0000000000000000FFFF0000 + ipv4 le
            if (hex.length == 32) return null
        }
        return try {
            if (hex.length != 32) return null
            // Detect IPv4-mapped ::ffff:a.b.c.d — last 8 hex often hold v4 in LE after ffff
            val lower = hex.lowercase(Locale.US)
            if (lower.startsWith("0000000000000000ffff") || lower.substring(16, 24) == "ffff0000") {
                // Common Android form: 0000000000000000FFFF0000 + IPv4 LE in last 4 bytes... 
                // Actually typical: 0000000000000000FFFF0000AABBCCDD where AABBCCDD is LE IPv4
            }
            val bytes = ByteArray(16)
            var i = 0
            while (i < 4) {
                val word = hex.substring(i * 8, i * 8 + 8).toLong(16)
                val base = i * 4
                bytes[base] = (word and 0xff).toByte()
                bytes[base + 1] = ((word shr 8) and 0xff).toByte()
                bytes[base + 2] = ((word shr 16) and 0xff).toByte()
                bytes[base + 3] = ((word shr 24) and 0xff).toByte()
                i++
            }
            // IPv4-mapped ::ffff:x.x.x.x
            val mapped = bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                bytes[2] == 0.toByte() && bytes[3] == 0.toByte() &&
                bytes[4] == 0.toByte() && bytes[5] == 0.toByte() &&
                bytes[6] == 0.toByte() && bytes[7] == 0.toByte() &&
                bytes[8] == 0xff.toByte() && bytes[9] == 0xff.toByte() &&
                bytes[10] == 0.toByte() && bytes[11] == 0.toByte()
            // Alternate Android: 0000:0000:0000:0000:0000:FFFF:w.x.y.z with different word order
            val mapped2 = bytes.sliceArray(0..9).all { it == 0.toByte() || (bytes[8] == 0xff.toByte() && bytes[9] == 0xff.toByte()) } &&
                bytes[8] == 0xff.toByte() && bytes[9] == 0xff.toByte()

            if (mapped || (bytes[8] == 0xff.toByte() && bytes[9] == 0xff.toByte() &&
                    bytes[0] == 0.toByte() && bytes[1] == 0.toByte())
            ) {
                val a = bytes[12].toInt() and 0xff
                val b = bytes[13].toInt() and 0xff
                val c = bytes[14].toInt() and 0xff
                val d = bytes[15].toInt() and 0xff
                // On some kernels mapped v4 sits at 12..15 already in network order after LE word decode
                if (a != 0 || b != 0 || c != 0 || d != 0) {
                    // Prefer if high bytes zero in first half
                    if (bytes.sliceArray(0..7).all { it == 0.toByte() }) {
                        return "$a.$b.$c.$d"
                    }
                }
            }
            // Also handle FFFF0000 in word 2 (index 8-11 as ff ff 0 0) with v4 in last word network after LE
            if (bytes[8] == 0xff.toByte() && bytes[9] == 0xff.toByte() && bytes[10] == 0.toByte() && bytes[11] == 0.toByte()) {
                val a = bytes[12].toInt() and 0xff
                val b = bytes[13].toInt() and 0xff
                val c = bytes[14].toInt() and 0xff
                val d = bytes[15].toInt() and 0xff
                return "$a.$b.$c.$d"
            }

            val sb = StringBuilder()
            for (w in 0 until 8) {
                if (w > 0) sb.append(':')
                val hi = bytes[w * 2].toInt() and 0xff
                val lo = bytes[w * 2 + 1].toInt() and 0xff
                sb.append(String.format(Locale.US, "%x", (hi shl 8) or lo))
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }
}
