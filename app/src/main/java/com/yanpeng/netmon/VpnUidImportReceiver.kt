package com.yanpeng.netmon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ADB: adb shell am broadcast -a com.yanpeng.netmon.IMPORT_VPN_UIDS \
 *   -n com.yanpeng.netmon/.VpnUidImportReceiver --es dumpsys "$(dumpsys connectivity)"
 * Prefer writing files/vpn_uids.txt via run-as for large payloads.
 */
class VpnUidImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val text = intent.getStringExtra(EXTRA_DUMPSYS)
            ?: intent.getStringExtra(EXTRA_UIDS)
            ?: return
        val n = VpnUidStore.importText(context.applicationContext, text, "broadcast")
        Log.i(TAG, "imported $n uid ranges")
    }

    companion object {
        const val ACTION = "com.yanpeng.netmon.IMPORT_VPN_UIDS"
        const val EXTRA_DUMPSYS = "dumpsys"
        const val EXTRA_UIDS = "uids"
        private const val TAG = "VpnUidImport"
    }
}
