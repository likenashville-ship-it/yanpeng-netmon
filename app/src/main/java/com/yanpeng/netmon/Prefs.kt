package com.yanpeng.netmon

import android.content.Context

object Prefs {
    private const val NAME = "netmon_prefs"
    private const val KEY_RECORDING = "recording"
    private const val KEY_INTERVAL = "interval_sec"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_SUMMARY_EXPANDED = "summary_expanded"

    fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isRecording(ctx: Context) = sp(ctx).getBoolean(KEY_RECORDING, false)
    fun setRecording(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_RECORDING, v).apply()

    fun intervalSec(ctx: Context) = sp(ctx).getInt(KEY_INTERVAL, 30).coerceIn(15, 600)
    fun setIntervalSec(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_INTERVAL, v.coerceIn(15, 600)).apply()

    fun autoStart(ctx: Context) = sp(ctx).getBoolean(KEY_AUTOSTART, false)
    fun setAutoStart(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_AUTOSTART, v).apply()

    /** Live-tab overview card; default collapsed so list stays visible. */
    fun summaryExpanded(ctx: Context) = sp(ctx).getBoolean(KEY_SUMMARY_EXPANDED, false)
    fun setSummaryExpanded(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SUMMARY_EXPANDED, v).apply()
}

