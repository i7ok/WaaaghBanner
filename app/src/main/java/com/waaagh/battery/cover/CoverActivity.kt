package com.waaagh.battery.cover

import android.app.Activity
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.waaagh.battery.BannerController
import com.waaagh.battery.core.DisplayState
import com.waaagh.battery.ui.BannerView

/**
 * Full-screen "banner only" screen for the cover display. Shown over the cover
 * lock screen; keeps the screen on while the phone is closed. Double tap leaves
 * it for a few seconds (CoverOverlayService brings it back).
 */
class CoverActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dm: DisplayManager
    private lateinit var banner: BannerView
    private var keepOn = false
    private var driftStep = 0

    private val stateListener: (DisplayState) -> Unit = { s ->
        banner.show(BannerController.renderer.base, BannerController.renderer.renderLetters(s.spec), s)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = updateKeepOn()
    }

    /** Move the whole picture by 2 px every minute so no pixel burns in. */
    private val drift = object : Runnable {
        override fun run() {
            val (dx, dy) = DRIFT[driftStep++ % DRIFT.size]
            banner.driftX = dx * 2f
            banner.driftY = dy * 2f
            main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BannerController.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(intent.getBooleanExtra(EXTRA_WAKE, false))
        dm = getSystemService(DisplayManager::class.java)
        banner = BannerView(this, fillParent = true)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                CoverOverlayService.pauseCover(HIDE_MS)
                finish()
                return true
            }
        })
        root.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }
        setContentView(root)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (onInnerScreen()) { finish(); return }        // phone opened and the app followed: leave
        visible = true
        dm.registerDisplayListener(displayListener, main)
        BannerController.addListener(stateListener)
        BannerController.republish()
        updateKeepOn()
        main.postDelayed(drift, 60_000)
    }

    override fun onStop() {
        visible = false
        main.removeCallbacks(drift)
        dm.unregisterDisplayListener(displayListener)
        BannerController.removeListener(stateListener)
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setTurnScreenOn(intent.getBooleanExtra(EXTRA_WAKE, false))
        if (intent.getBooleanExtra(EXTRA_WAKE, false)) {
            // already on top but the screen dozes: turnScreenOn acts when the window is (re)shown
            Log.i("WAAAGH", "cover dozing, re-showing with turnScreenOn")
            recreate()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (onInnerScreen()) finish()                    // moved to the inner screen: leave
    }

    private fun onInnerScreen() = (display?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY

    /** Keep the cover screen on whenever it is lit, open or closed phone alike. */
    private fun updateKeepOn() {
        val closed = true
        if (closed == keepOn) return
        keepOn = closed
        if (closed) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i("WAAAGH", "cover keep-on = $closed")
    }

    companion object {
        private const val HIDE_MS = 10_000L
        const val EXTRA_WAKE = "wake"
        private val DRIFT = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        @Volatile var visible = false
            private set
    }
}
