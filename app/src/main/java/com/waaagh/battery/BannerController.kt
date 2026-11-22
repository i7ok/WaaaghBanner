package com.waaagh.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.waaagh.battery.core.BannerStateMachine
import com.waaagh.battery.core.Cancellable
import com.waaagh.battery.core.DisplayState
import com.waaagh.battery.core.Scheduler
import com.waaagh.battery.events.CallMonitor
import com.waaagh.battery.widget.BannerRenderer
import com.waaagh.battery.widget.WidgetUpdater
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide hub: event sources feed the state machine, the state machine
 * feeds the widget and the test screen. Everything runs on the main thread.
 */
object BannerController {
    private const val TAG = "WAAAGH"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var machine: BannerStateMachine
    private val listeners = CopyOnWriteArraySet<(DisplayState) -> Unit>()

    val renderer: BannerRenderer by lazy { BannerRenderer(app) }

    @Volatile var notificationAccess = false
        private set

    val state: DisplayState get() = machine.current
    val realLevel: Int get() = machine.realLevel
    val testLevel: Int? get() = machine.testLevel
    val ringing: Boolean get() = machine.ringing

    private val scheduler = object : Scheduler {
        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            val r = Runnable(block)
            main.postDelayed(r, delayMs)
            return Cancellable { main.removeCallbacks(r) }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = handleBattery(i)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // cover screen woke up: make sure the picture and the charge are current
            refreshBatteryNow()
            WidgetUpdater.push(app, machine.current)
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        machine = BannerStateMachine(SystemClock::uptimeMillis, scheduler) { s -> onState(s) }
        val sticky = app.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { handleBattery(it) }
        app.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        CallMonitor.refresh(app)
        Log.i(TAG, "controller started, battery=${machine.realLevel}")
    }

    private fun handleBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) machine.onBattery(Math.round(level * 100f / scale))
    }

    /** Ask the system for the current value (sticky broadcast). */
    fun refreshBatteryNow() {
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { handleBattery(it) }
    }

    private fun onState(s: DisplayState) {
        WidgetUpdater.push(app, s)
        listeners.forEach { it(s) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ---- inputs -----------------------------------------------------------
    fun onNewNotification() = onMain { machine.onNewNotification() }
    fun onCallRinging() = onMain { machine.onCallRinging() }
    fun onCallEnded() = onMain { machine.onCallEnded() }
    fun setTestLevel(level: Int?) = onMain { machine.setTestLevel(level) }
    fun setNotificationAccess(on: Boolean) { notificationAccess = on; onMain { listeners.forEach { it(machine.current) } } }

    /** Re-draw everything (widget added/resized, settings changed). */
    fun republish() = onMain {
        refreshBatteryNow()
        machine.republish()
    }

    fun addListener(l: (DisplayState) -> Unit) { listeners += l; l(machine.current) }
    fun removeListener(l: (DisplayState) -> Unit) { listeners -= l }
}
