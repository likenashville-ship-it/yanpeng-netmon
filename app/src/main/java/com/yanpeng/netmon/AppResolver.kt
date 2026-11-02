package com.yanpeng.netmon

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

data class ResolvedApp(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val allPackages: List<String>
)

/**
 * Resolve NetworkStats / proc uid -> human-readable Chinese label + icon.
 * Relies on QUERY_ALL_PACKAGES (or broad &lt;queries&gt;) on Android 11+.
 */
object AppResolver {

    @Volatile private var uidIndex: Map<Int, List<String>>? = null
    @Volatile private var indexBuiltForPm: Int = 0

    fun invalidate() {
        uidIndex = null
        indexBuiltForPm = 0
    }

    fun resolve(pm: PackageManager, uid: Int): ResolvedApp {
        val pkgs = packagesForUid(pm, uid)
        if (pkgs.isEmpty()) {
            val alias = systemAlias(uid)
            return if (alias != null) {
                ResolvedApp(uid, "uid:$uid", alias, null, emptyList())
            } else {
                ResolvedApp(uid, "uid:$uid", "未知应用 (uid $uid)", null, emptyList())
            }
        }

        val preferred = pickPreferred(pm, pkgs)
        var label: String? = null
        var icon: Drawable? = null
        var chosen = preferred ?: pkgs.first()

        for (pkg in listOfNotNull(preferred) + pkgs) {
            try {
                val ai = appInfo(pm, pkg) ?: continue
                label = pm.getApplicationLabel(ai).toString()
                icon = try { pm.getApplicationIcon(ai) } catch (_: Exception) { null }
                chosen = pkg
                break
            } catch (_: Exception) {
            }
        }

        // Shared system uid with many pkgs but no readable label: use alias + first pkg
        if (label.isNullOrBlank()) {
            val alias = systemAlias(uid)
            label = alias ?: chosen
        }

        // If label is just the package and we have a system alias for shared uid, prefer alias as prefix
        if (uid < 10000) {
            systemAlias(uid)?.let { alias ->
                if (label == chosen || label!!.startsWith("android.") || label == "uid:$uid") {
                    label = alias
                }
            }
        }

        val extra = if (pkgs.size > 1) " (+${pkgs.size - 1})" else ""
        return ResolvedApp(
            uid = uid,
            packageName = chosen + extra,
            appName = label ?: "未知应用 (uid $uid)",
            icon = icon,
            allPackages = pkgs
        )
    }

    fun primaryPackage(packageName: String): String {
        val idx = packageName.indexOf(" (+")
        return if (idx > 0) packageName.substring(0, idx) else packageName
    }

    private fun packagesForUid(pm: PackageManager, uid: Int): List<String> {
        val direct = try {
            pm.getPackagesForUid(uid)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (direct.isNotEmpty()) return direct.distinct()

        // Fallback: scan installed apps (needs QUERY_ALL_PACKAGES)
        val index = ensureUidIndex(pm)
        return index[uid].orEmpty()
    }

    private fun ensureUidIndex(pm: PackageManager): Map<Int, List<String>> {
        val key = System.identityHashCode(pm)
        uidIndex?.let { if (indexBuiltForPm == key) return it }
        val map = HashMap<Int, MutableList<String>>()
        try {
            val apps: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (ai in apps) {
                map.getOrPut(ai.uid) { mutableListOf() }.add(ai.packageName)
            }
        } catch (_: Exception) {
        }
        uidIndex = map
        indexBuiltForPm = key
        return map
    }

    private fun pickPreferred(pm: PackageManager, pkgs: List<String>): String? {
        // Prefer a package that has a MAIN/LAUNCHER activity (user-visible app)
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchers = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(launch, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launch, 0)
            }.map { it.activityInfo.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        pkgs.firstOrNull { it in launchers }?.let { return it }

        // Prefer non-system-looking package name
        pkgs.firstOrNull { !it.startsWith("com.android.") && !it.startsWith("android.") }?.let { return it }
        return pkgs.firstOrNull()
    }

    private fun appInfo(pm: PackageManager, pkg: String): ApplicationInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Friendly aliases for well-known / reserved UIDs when packages are unavailable. */
    fun systemAlias(uid: Int): String? = when (uid) {
        0 -> "内核 / root"
        1000 -> "系统"
        1001 -> "电话"
        1002 -> "蓝牙"
        1003 -> "图形 / Graphics"
        1005 -> "SDCard 读写"
        1006 -> "Wi‑Fi"
        1007 -> "多播"
        1010 -> "VPN 网络"
        1013 -> "媒体"
        1016 -> "媒体编解码"
        1019 -> "DRM"
        1021 -> "VPN"
        1023 -> "媒体存储"
        1027 -> "NFC"
        1031 -> "DHCP"
        1036 -> "日志"
        1037 -> "DNS 解析"
        1041 -> "审计"
        1047 -> "度量"
        1051 -> "网络栈"
        1053 -> "WebView zygote"
        1068 -> "网络受限"
        1073 -> "网络栈"
        2000 -> "ADB shell"
        else -> when {
            uid in 10000..19999 -> null // normal apps — should have packages
            uid in 99000..99999 -> "隔离进程 (uid $uid)"
            uid >= 90000 -> "应用沙箱 (uid $uid)"
            else -> null
        }
    }
}
