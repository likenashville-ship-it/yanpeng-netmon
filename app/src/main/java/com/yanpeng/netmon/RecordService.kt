package com.yanpeng.netmon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yanpeng.netmon.db.AppDatabase
import com.yanpeng.netmon.db.SampleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setRecording(this, false)
                stopSelfSafely()
                return START_NOT_STICKY
            }
            else -> {
                Prefs.setRecording(this, true)
                ensureForeground()
                sampleOnce()
                scheduleNext()
                return START_STICKY
            }
        }
    }

    private fun ensureForeground() {
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val interval = Prefs.intervalSec(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("每 ${interval}s 采样流量 · 点此打开 · 可停止")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun scheduleNext() {
        tick?.let { handler.removeCallbacks(it) }
        val delay = Prefs.intervalSec(this) * 1000L
        val r = Runnable {
            if (!Prefs.isRecording(this)) {
                stopSelfSafely()
                return@Runnable
            }
            sampleOnce()
            scheduleNext()
        }
        tick = r
        handler.postDelayed(r, delay)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun sampleOnce() {
        scope.launch {
            try {
                if (!TrafficProbe.hasUsageAccess(applicationContext)) return@launch
                val intervalMs = Prefs.intervalSec(applicationContext) * 1000L
                // look slightly longer than interval so buckets aren't empty at boundaries
                val window = (intervalMs * 2).coerceIn(15_000L, 600_000L)
                val rows = TrafficProbe.recent(applicationContext, window)
                val netType = NetworkSummary.currentType(applicationContext)
                val now = System.currentTimeMillis()
                val entities = rows
                    .filter { it.total > 0 }
                    .take(80)
                    .map {
                        SampleEntity(
                            ts = now,
                            uid = it.uid,
                            packageName = it.packageName,
                            appName = it.appName,
                            tcpCount = 0,
                            rxDelta = it.rx,
                            txDelta = it.tx,
                            byteDelta = it.total,
                            networkType = netType,
                            source = "traffic"
                        )
                    }
                val dao = AppDatabase.get(applicationContext).sampleDao()
                if (entities.isNotEmpty()) dao.insertAll(entities)
                dao.deleteOlderThan(now - 7L * 24 * 60 * 60 * 1000)
            } catch (_: Exception) {
            }
        }
    }

    private fun stopSelfSafely() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tick?.let { handler.removeCallbacks(it) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "netmon_record"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.yanpeng.netmon.STOP"

        fun start(ctx: Context) {
            Prefs.setRecording(ctx, true)
            Prefs.setAutoStart(ctx, true)
            val i = Intent(ctx, RecordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            Prefs.setRecording(ctx, false)
            val i = Intent(ctx, RecordService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(i)
            } catch (_: Exception) {
                ctx.stopService(Intent(ctx, RecordService::class.java))
            }
        }
    }
}
