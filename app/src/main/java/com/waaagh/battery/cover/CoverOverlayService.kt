package com.waaagh.battery.cover

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.waaagh.battery.BannerController
import com.waaagh.battery.core.DisplayState
import com.waaagh.battery.core.Mode

/**
 * Shows [CoverActivity] on the cover display when the cover lights up and when
 * a new notification arrives while it is lit. Samsung gives accessibility
 * overlays no token on the cover, but lets activities run there; a system-bound
 * accessibility service may start them from the background. Never wakes the
 * screen; once the user taps the banner away it stays away until the next
 * light-up or notification.
 *
 * (The class name is historical; renaming it would switch the accessibility
 * service off and the user would have to enable it again.)
 */
class CoverOverlayService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dm: DisplayManager
    private var coverWasOn = false
    private var lastEffect = -1

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            val on = coverLit()
            if (on && !coverWasOn) showBanner("cover lit")
            coverWasOn = on
        }
    }

    private val stateListener: (DisplayState) -> Unit = { s ->
        if (s.mode == Mode.NOTIFICATION && s.effectId != lastEffect) {
            lastEffect = s.effectId
            // Samsung lights the cover for notifications itself; if it's lit already
            // (or a moment later) the banner comes up red and shaking.
            main.postDelayed({ if (coverLit()) showBanner("notification") }, 150)
        }
    }

    override fun onServiceConnected() {
        instance = this
        BannerController.init(this)
        dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, main)
        coverWasOn = coverLit()
        lastEffect = BannerController.state.effectId
        BannerController.addListener(stateListener)
        status = "готова"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        BannerController.removeListener(stateListener)
        if (::dm.isInitialized) dm.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    /** Samsung hides the cover display from getDisplays(); ask for ids directly. */
    private fun findCover(): Display? {
        for (id in 1..8) dm.getDisplay(id)?.let { return it }
        return null
    }

    /** Cover lit and the phone closed (inner screen not on). */
    private fun coverLit(): Boolean {
        val cover = findCover() ?: return false
        return cover.state == Display.STATE_ON &&
            dm.getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON
    }

    private fun showBanner(why: String) {
        if (CoverActivity.visible) return
        val cover = findCover() ?: run { status = "внешний экран не найден"; return }
        try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(cover.displayId)
            startActivity(Intent(this, CoverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION), opts.toBundle())
            status = "баннер показан ($why)"
            Log.i(TAG, "banner on cover: $why")
        } catch (e: Exception) {
            status = "ошибка запуска: ${e.javaClass.simpleName}"
            Log.e(TAG, "cannot launch cover activity", e)
        }
    }

    companion object {
        private const val TAG = "WAAAGH"
        @Volatile private var instance: CoverOverlayService? = null
        val running: Boolean get() = instance != null
        @Volatile var status = "служба выключена"
    }
}
