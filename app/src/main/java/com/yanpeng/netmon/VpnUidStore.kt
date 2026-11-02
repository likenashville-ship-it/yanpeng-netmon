package com.yanpeng.netmon

import android.content.Context
import java.io.File

/**
 * Third-party apps get NetworkCapabilities with Uids stripped by the system.
 * We keep a local cache of Clash/VPN UID ranges, filled from:
 *  - dumpsys connectivity text (via ADB sync / broadcast / dropped file)
 *  - rare cases where live getUids() works
 */
object VpnUidStore {

    private const val PREF_RANGES = "vpn_uid_ranges"
    private const val PREF_SOURCE = "vpn_uid_source"
    private const val PREF_AT = "vpn_uid_at"
    private const val FILE_NAME = "vpn_uids.txt"

    data class Snapshot(
        val ranges: List<Pair<Int, Int>>,
        val source: String,
        val savedAt: Long
    ) {
        val isEmpty: Boolean get() = ranges.isEmpty()
        fun ageMs(now: Long = System.currentTimeMillis()) = (now - savedAt).coerceAtLeast(0L)
    }

    fun load(ctx: Context): Snapshot {
        // Prefer on-disk file (ADB / run-as can update without SharedPreferences)
        val fromFile = readFile(ctx)
        if (fromFile.isNotEmpty()) {
            savePrefs(ctx, fromFile, "file")
            return Snapshot(fromFile, "file", Prefs.sp(ctx).getLong(PREF_AT, System.currentTimeMillis()))
        }
        val raw = Prefs.sp(ctx).getString(PREF_RANGES, "").orEmpty()
        val ranges = decode(raw)
        return Snapshot(
            ranges,
            Prefs.sp(ctx).getString(PREF_SOURCE, "").orEmpty().ifBlank { "prefs" },
            Prefs.sp(ctx).getLong(PREF_AT, 0L)
        )
    }

    fun save(ctx: Context, ranges: List<Pair<Int, Int>>, source: String) {
        if (ranges.isEmpty()) return
        val distinct = ranges.distinct().sortedBy { it.first }
        savePrefs(ctx, distinct, source)
        writeFile(ctx, distinct)
    }

    /** Accept full dumpsys connectivity output or a bare Uids body / line list. */
    fun importText(ctx: Context, text: String, source: String): Int {
        val ranges = parseRanges(text)
        if (ranges.isEmpty()) return 0
        save(ctx, ranges, source)
        return ranges.size
    }

    fun clear(ctx: Context) {
        Prefs.sp(ctx).edit()
            .remove(PREF_RANGES)
            .remove(PREF_SOURCE)
            .remove(PREF_AT)
            .apply()
        try { file(ctx).delete() } catch (_: Exception) {}
        try { externalFile(ctx)?.delete() } catch (_: Exception) {}
    }

    fun parseRanges(text: String): List<Pair<Int, Int>> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Pair<Int, Int>>()
        // dumpsys: Uids: <{a-b, c-c, ...}>
        val block = Regex("""Uids:\s*<\{([^}]*)\}>""").find(text)?.groupValues?.get(1)
        val body = block ?: text
        for (part in body.split(',', '\n', ';', ' ')) {
            val t = part.trim().trimStart('{').trimEnd('}')
            if (t.isEmpty() || t.startsWith("#")) continue
            val r = Regex("""^(\d+)\s*-\s*(\d+)$""").find(t)
            if (r != null) {
                val a = r.groupValues[1].toInt()
                val b = r.groupValues[2].toInt()
                if (a <= b) out += a to b
                continue
            }
            val one = t.toIntOrNull()
            if (one != null) out += one to one
        }
        return out.distinct()
    }

    private fun encode(ranges: List<Pair<Int, Int>>): String =
        ranges.joinToString(",") { (a, b) -> if (a == b) "$a" else "$a-$b" }

    private fun decode(raw: String): List<Pair<Int, Int>> = parseRanges(raw)

    private fun savePrefs(ctx: Context, ranges: List<Pair<Int, Int>>, source: String) {
        Prefs.sp(ctx).edit()
            .putString(PREF_RANGES, encode(ranges))
            .putString(PREF_SOURCE, source)
            .putLong(PREF_AT, System.currentTimeMillis())
            .apply()
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun externalFile(ctx: Context): File? =
        ctx.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    private fun readFile(ctx: Context): List<Pair<Int, Int>> {
        val candidates = listOfNotNull(file(ctx), externalFile(ctx))
        for (f in candidates) {
            try {
                if (!f.isFile || f.length() == 0L) continue
                val text = f.readText()
                val ranges = parseRanges(text)
                if (ranges.isNotEmpty()) return ranges
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun writeFile(ctx: Context, ranges: List<Pair<Int, Int>>) {
        val body = buildString {
            appendLine("# Clash/VPN proxied UID ranges")
            appendLine("# synced ${System.currentTimeMillis()}")
            for ((a, b) in ranges) {
                if (a == b) appendLine("$a") else appendLine("$a-$b")
            }
        }
        try { file(ctx).writeText(body) } catch (_: Exception) {}
        try { externalFile(ctx)?.writeText(body) } catch (_: Exception) {}
    }
}
