package com.waaagh.battery.events

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.waaagh.battery.BannerController
import com.waaagh.battery.R

/**
 * Keeps the process (and so the dynamic battery receiver and the call callback)
 * alive while no notification access keeps it bound. Without POST_NOTIFICATIONS
 * its notification is not shown in the shade.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BannerController.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Фоновая работа", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            })
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_waaagh)
            .setContentTitle("WAAAGH! следит за зарядом")
            .setOngoing(true)
            .build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.w("WAAAGH", "startForeground", e)
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "keepalive"
        private const val PREFS = "service"

        fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("on", true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("on", on).apply()
            if (on) tryStart(ctx) else ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }

        /** Background starts can be refused by the OS; that's fine, we retry later. */
        fun tryStart(ctx: Context) {
            if (!enabled(ctx)) return
            try {
                ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.i("WAAAGH", "keep-alive not started now: ${e.javaClass.simpleName}")
            }
        }
    }
}
