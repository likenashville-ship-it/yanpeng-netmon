package com.yanpeng.netmon

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.os.RemoteException
import android.telephony.TelephonyManager

data class TrafficRow(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val rx: Long,
    val tx: Long
) {
    val total: Long get() = rx + tx
}

object TrafficProbe {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Query NetworkStats summary for [start, end) and aggregate rx/tx by uid.
     * Covers Wi‑Fi and mobile.
     */
    fun snapshot(context: Context, start: Long, end: Long): List<TrafficRow> {
        if (!hasUsageAccess(context)) return emptyList()
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val agg = HashMap<Int, LongArray>() // uid -> [rx, tx]

        fun absorb(networkType: Int, subscriberId: String?) {
            var stats: NetworkStats? = null
            try {
                stats = nsm.querySummary(networkType, subscriberId, start, end)
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val uid = bucket.uid
                    if (uid < 0 ||
                        uid == NetworkStats.Bucket.UID_ALL ||
                        uid == NetworkStats.Bucket.UID_TETHERING ||
                        uid == NetworkStats.Bucket.UID_REMOVED
                    ) continue
                    val arr = agg.getOrPut(uid) { longArrayOf(0L, 0L) }
                    arr[0] += bucket.rxBytes
                    arr[1] += bucket.txBytes
                }
            } catch (_: SecurityException) {
            } catch (_: RemoteException) {
            } catch (_: IllegalStateException) {
            } catch (_: Exception) {
            } finally {
                try { stats?.close() } catch (_: Exception) {}
            }
        }

        absorb(ConnectivityManager.TYPE_WIFI, "")
        absorb(ConnectivityManager.TYPE_WIFI, null)

        var subId: String? = null
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION", "MissingPermission")
            subId = tm.subscriberId
        } catch (_: Exception) {
        }
        absorb(ConnectivityManager.TYPE_MOBILE, subId)
        absorb(ConnectivityManager.TYPE_MOBILE, null)
        absorb(ConnectivityManager.TYPE_MOBILE, "")

        val pm = context.packageManager
        return agg.entries
            .filter { it.value[0] + it.value[1] > 0 }
            .map { (uid, bytes) ->
                val r = AppResolver.resolve(pm, uid)
                TrafficRow(
                    uid,
                    AppResolver.primaryPackage(r.packageName),
                    r.appName,
                    bytes[0],
                    bytes[1]
                )
            }
            .sortedByDescending { it.total }
    }

    /** Traffic in the last [windowMs] milliseconds. */
    fun recent(context: Context, windowMs: Long): List<TrafficRow> {
        val end = System.currentTimeMillis()
        val start = (end - windowMs.coerceAtLeast(3_000L)).coerceAtLeast(0L)
        return snapshot(context, start, end)
    }
}
