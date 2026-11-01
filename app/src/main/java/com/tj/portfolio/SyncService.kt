package com.tj.portfolio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * KEEPS A USER-STARTED OFFLINE REFRESH ALIVE WHILE THE APP IS IN THE BACKGROUND (2026-11-01).
 *
 * Tj: "make the app still properly download and refresh even if it is in the background. When I
 * pull down to refresh and switch apps ... it says interrupted." Cancelling the work was one
 * half; the other is that a backgrounded process with no foreground component is frozen and
 * loses network within moments. A `dataSync` foreground service (with its ongoing notification)
 * is the platform's sanctioned way to finish a download the user asked for.
 *
 * It does NO work itself: [com.tj.portfolio.ui.OfflineSync] runs as before. The service is
 * started when a pass begins (the app is in the foreground then, which is what Android 12+
 * requires for starting one) and stopped the moment the pass ends, so nothing lingers.
 */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Offline refresh", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Saving your portfolio for offline")
            .setContentText("Refreshing prices, charts, news and reports…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (_: Exception) {
            // Not allowed to run as a foreground service right now: the refresh simply carries on
            // as an ordinary foreground-only pass.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "offline_refresh"
        private const val ID = 4107

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, SyncService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, SyncService::class.java)) }
        }
    }
}
