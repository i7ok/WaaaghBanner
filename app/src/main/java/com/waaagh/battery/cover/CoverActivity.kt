package com.waaagh.battery.cover

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.waaagh.battery.BannerController
import com.waaagh.battery.core.DisplayState
import com.waaagh.battery.ui.BannerView

/**
 * Banner on the cover screen, shown over the stock cover (clock, notifications,
 * music) when the cover lights up or a notification arrives. A tap on the
 * banner closes it and leaves the stock cover with all its features. The screen
 * itself is never kept on or woken by us.
 */
class CoverActivity : Activity() {
    private lateinit var banner: BannerView
    private var shownAt = 0L

    private val stateListener: (DisplayState) -> Unit = { s ->
        banner.show(BannerController.renderer.base, BannerController.renderer.renderLetters(s.spec), s)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BannerController.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(false)
        banner = BannerView(this, fillParent = true)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // Tap = go to the stock cover. A short guard so the touch that woke the
        // screen (or is still in progress when we appear) doesn't close us at once.
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - shownAt > GUARD_MS) finish()
            true
        }
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
        shownAt = SystemClock.uptimeMillis()
        BannerController.addListener(stateListener)
        BannerController.republish()                     // fresh charge every time it appears
    }

    override fun onStop() {
        visible = false
        BannerController.removeListener(stateListener)
        super.onStop()
        // Gone from view (screen off, tap, phone opened): next time start fresh on
        // the stock cover rule — the service brings us back on light-up/notification.
        if (!isFinishing) finish()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (onInnerScreen()) finish()
    }

    private fun onInnerScreen() = (display?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY

    companion object {
        private const val GUARD_MS = 350L
        @Volatile var visible = false
            private set
    }
}
