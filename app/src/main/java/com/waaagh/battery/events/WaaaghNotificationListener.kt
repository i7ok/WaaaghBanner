package com.waaagh.battery.events

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.waaagh.battery.BannerController

/**
 * Starts the notification effect only for notifications that are genuinely new:
 * keys already shown (updates) and removals do nothing.
 */
class WaaaghNotificationListener : NotificationListenerService() {
    private val known = HashSet<String>()

    override fun onListenerConnected() {
        BannerController.init(this)
        known.clear()
        try { activeNotifications?.forEach { known += it.key } } catch (e: Exception) { Log.w(TAG, "snapshot", e) }
        BannerController.setNotificationAccess(true)
    }

    override fun onListenerDisconnected() {
        BannerController.setNotificationAccess(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val isNew = known.add(sbn.key)
        if (!isNew) return                     // update of an already shown notification
        if (!qualifies(sbn, rankingMap)) return
        Log.d(TAG, "new notification from ${sbn.packageName}")
        BannerController.onNewNotification()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        known.remove(sbn.key)
    }

    private fun qualifies(sbn: StatusBarNotification, ranking: RankingMap?): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName && n.channelId != TEST_CHANNEL) return false
        val f = n.flags
        if (f and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (f and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (f and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // the dialer's own notifications never start a second effect
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL) return false
        if (sbn.packageName in DIALERS) return false
        if (ranking != null) {
            val r = Ranking()
            if (ranking.getRanking(sbn.key, r) && r.importance <= NotificationManager.IMPORTANCE_MIN) return false
        }
        return true
    }

    companion object {
        private const val TAG = "WAAAGH"
        const val TEST_CHANNEL = "waaagh_test"
        val DIALERS = setOf(
            "com.samsung.android.incallui", "com.samsung.android.dialer", "com.android.server.telecom",
            "com.android.dialer", "com.google.android.dialer", "com.android.phone",
        )
    }
}
