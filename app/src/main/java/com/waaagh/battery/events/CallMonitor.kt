package com.waaagh.battery.events

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.waaagh.battery.BannerController

/** Ordinary mobile calls via TelephonyCallback.CallStateListener (RINGING). */
object CallMonitor {
    private var callback: TelephonyCallback? = null

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    val active: Boolean get() = callback != null

    fun refresh(ctx: Context) {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        if (hasPermission(ctx)) {
            if (callback != null) return
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handle(state)
            }
            try {
                tm.registerTelephonyCallback(ctx.mainExecutor, cb)
                callback = cb
            } catch (e: SecurityException) {
                Log.w("WAAAGH", "telephony callback", e)
            }
        } else {
            callback?.let { tm.unregisterTelephonyCallback(it) }
            callback = null
            BannerController.onCallEnded()
        }
    }

    fun handle(state: Int) {
        if (state == TelephonyManager.CALL_STATE_RINGING) BannerController.onCallRinging()
        else BannerController.onCallEnded()   // OFFHOOK = answered, IDLE = rejected/missed/ended
    }
}

/** Backup source: wakes the process for a call even if it had been killed. */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        BannerController.init(ctx)
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallMonitor.handle(TelephonyManager.CALL_STATE_RINGING)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallMonitor.handle(TelephonyManager.CALL_STATE_OFFHOOK)
            TelephonyManager.EXTRA_STATE_IDLE -> CallMonitor.handle(TelephonyManager.CALL_STATE_IDLE)
        }
        CallMonitor.refresh(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        BannerController.init(ctx)
        BannerController.republish()
        KeepAliveService.tryStart(ctx)
    }
}
