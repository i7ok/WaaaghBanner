package com.waaagh.battery.cover

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.waaagh.battery.BannerController

/**
 * Keeper of the cover screen. Samsung gives accessibility overlays no token on
 * the cover display, but lets activities run there, so this service keeps
 * [CoverActivity] on the cover display (a system-bound accessibility service
 * may start activities from the background). It also keeps the process alive.
 */
class CoverOverlayService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dm: DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = ensureCover()
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = ensureCover()
    }

    /** Safety net: re-check every 5 s (e.g. something else took the cover). */
    private val watchdog = object : Runnable {
        override fun run() { ensureCover(); main.postDelayed(this, 5_000) }
    }

    /** Hinge angle: ~0 closed, ~180 open. Unknown = treat as closed only if inner screen is off. */
    @Volatile private var hinge = -1f
    private val hingeListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val was = closed()
            hinge = e.values[0]
            if (closed() != was) { Log.i(TAG, "hinge ${hinge.toInt()}°, closed=${closed()}"); ensureCover() }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun closed(): Boolean =
        if (hinge >= 0f) hinge < 30f
        else dm.getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON

    override fun onServiceConnected() {
        instance = this
        getSystemService(SensorManager::class.java)?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let { sm.registerListener(hingeListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        BannerController.init(this)
        dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, main)
        main.post(watchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        if (::dm.isInitialized) dm.unregisterDisplayListener(displayListener)
        getSystemService(SensorManager::class.java)?.unregisterListener(hingeListener)
        super.onDestroy()
    }

    /** Samsung hides the cover display from getDisplays(); ask for ids directly. */
    private fun findCover(): Display? {
        for (id in 1..8) dm.getDisplay(id)?.let { return it }
        return null
    }

    private fun ensureCover() {
        val cover = findCover() ?: run { status = "внешний экран не найден"; return }
        val lit = cover.state == Display.STATE_ON
        if (CoverActivity.visible && (lit || !closed())) { status = "баннер на внешнем экране"; return }
        if (SystemClock.uptimeMillis() < pausedUntil) { status = "скрыт двойным тапом"; return }
        // Closed phone: wake the cover screen (always on). Open phone: never wake —
        // that would switch the inner screen on too.
        val wake = closed()
        try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(cover.displayId)
            startActivity(Intent(this, CoverActivity::class.java)
                .putExtra(CoverActivity.EXTRA_WAKE, wake)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION), opts.toBundle())
            status = "запускаю баннер на внешнем экране"
            Log.i(TAG, "launched cover activity on display ${cover.displayId}")
        } catch (e: Exception) {
            status = "ошибка запуска: ${e.javaClass.simpleName}"
            Log.e(TAG, "cannot launch cover activity", e)
        }
    }

    companion object {
        private const val TAG = "WAAAGH"
        @Volatile private var instance: CoverOverlayService? = null
        @Volatile private var pausedUntil = 0L
        val running: Boolean get() = instance != null
        @Volatile var status = "служба выключена"

        fun pauseCover(ms: Long) {
            pausedUntil = SystemClock.uptimeMillis() + ms
            instance?.let { s -> s.main.postDelayed({ s.ensureCover() }, ms + 100) }
        }
    }
}
