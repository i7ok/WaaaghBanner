package com.waaagh.battery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import com.waaagh.battery.BannerController
import com.waaagh.battery.R
import com.waaagh.battery.core.DisplayState
import com.waaagh.battery.core.Mode
import com.waaagh.battery.core.ShakePattern
import com.waaagh.battery.ui.TestActivity

/**
 * Builds RemoteViews of the home-screen widget for the current state.
 *
 * Shake methods (switch on the test screen):
 *  - FLIPPER: pre-rendered positions of the letter layer inside a ViewFlipper,
 *    flipped by the host itself.
 *  - MANUAL:  one ImageView, our process moves it with partial updates.
 */
object WidgetUpdater {
    private const val TAG = "WAAAGH"
    private const val PREFS = "widget"
    private val main = Handler(Looper.getMainLooper())

    enum class ShakeMethod { FLIPPER, MANUAL }

    private val NOTIFY_IDS = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4, R.id.n5, R.id.n6, R.id.n7)
    private val CALL_IDS = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5, R.id.c6, R.id.c7, R.id.c8, R.id.c9)

    @Volatile var lastStatus: String = "ещё не обновлялся"
        private set

    fun shakeMethod(ctx: Context): ShakeMethod =
        ShakeMethod.valueOf(ctx.getSharedPreferences(PREFS, 0).getString("shake", ShakeMethod.FLIPPER.name)!!)

    fun setShakeMethod(ctx: Context, m: ShakeMethod) =
        ctx.getSharedPreferences(PREFS, 0).edit().putString("shake", m.name).apply()

    fun widgetIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WaaaghWidgetProvider::class.java))

    // ------------------------------------------------------------------------
    fun push(ctx: Context, s: DisplayState) {
        stopManual()
        val ids = widgetIds(ctx)
        if (ids.isEmpty()) { lastStatus = "виджет не добавлен"; return }
        val renderer = BannerController.renderer
        val t0 = SystemClock.uptimeMillis()
        val bmp = renderer.renderLetters(s.spec)
        val method = shakeMethod(ctx)
        val mgr = AppWidgetManager.getInstance(ctx)
        val ok = sendWithFallback(mgr, ids, bmp) { b -> build(ctx, s, method, b) }
        if (ok && method == ShakeMethod.MANUAL && s.mode != Mode.BATTERY) startManual(ctx, ids, s)
        Log.d(TAG, "widget ${s.mode} ${s.level}% via $method in ${SystemClock.uptimeMillis() - t0} ms")
    }

    private fun build(ctx: Context, s: DisplayState, method: ShakeMethod, bmp: Bitmap): RemoteViews {
        val unit = ctx.resources.getDimensionPixelSize(R.dimen.shake_unit)
        val margin = ctx.resources.getDimensionPixelSize(R.dimen.shake_margin)
        val rv = when {
            s.mode == Mode.BATTERY || method == ShakeMethod.MANUAL ->
                RemoteViews(ctx.packageName, R.layout.widget_static).apply {
                    setImageViewBitmap(R.id.letters, bmp)
                }
            s.mode == Mode.NOTIFICATION -> flipper(ctx, R.layout.widget_notify, NOTIFY_IDS, ShakePattern.NOTIFY, bmp, unit, margin)
            else -> flipper(ctx, R.layout.widget_call, CALL_IDS, ShakePattern.CALL, bmp, unit, margin)
        }
        // All paddings in px from one place: XML dimens are resolved with the host
        // display's density, which may differ from ours (cover screen is 160 dpi).
        rv.setViewPadding(R.id.base, margin, 0, margin, 0)
        if (s.mode == Mode.BATTERY || method == ShakeMethod.MANUAL) rv.setViewPadding(R.id.letters, margin, 0, margin, 0)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, TestActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        rv.setOnClickPendingIntent(R.id.root, open)
        return rv
    }

    private fun flipper(ctx: Context, layout: Int, ids: IntArray, pattern: IntArray, bmp: Bitmap, unit: Int, margin: Int) =
        RemoteViews(ctx.packageName, layout).apply {
            for (i in ids.indices) {
                // same Bitmap object everywhere -> stored once in the RemoteViews bitmap cache
                setImageViewBitmap(ids[i], bmp)
                val dx = pattern[i] * unit
                setViewPadding(ids[i], margin + dx, 0, margin - dx, 0)
            }
        }

    /** Full-size bitmap first; if the binder refuses it, retry at half size. */
    private fun sendWithFallback(mgr: AppWidgetManager, ids: IntArray, bmp: Bitmap, make: (Bitmap) -> RemoteViews): Boolean {
        return try {
            mgr.updateAppWidget(ids, make(bmp))
            lastStatus = "ок, ${bmp.width}×${bmp.height}"
            true
        } catch (e: Exception) {
            Log.w(TAG, "full-size widget update failed", e)
            try {
                val half = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
                mgr.updateAppWidget(ids, make(half))
                lastStatus = "ок только в половинном размере (${e.javaClass.simpleName})"
                true
            } catch (e2: Exception) {
                Log.e(TAG, "widget update failed", e2)
                lastStatus = "ошибка: ${e2.javaClass.simpleName}"
                false
            }
        }
    }

    // ---- manual shake ------------------------------------------------------
    private var manual: Runnable? = null

    private fun stopManual() {
        manual?.let { main.removeCallbacks(it) }
        manual = null
    }

    private fun startManual(ctx: Context, ids: IntArray, s: DisplayState) {
        val loop = s.mode == Mode.CALL
        val pattern = if (loop) ShakePattern.CALL else ShakePattern.NOTIFY
        val frameMs = if (loop) ShakePattern.CALL_FRAME_MS else ShakePattern.NOTIFY_FRAME_MS
        val unit = ctx.resources.getDimensionPixelSize(R.dimen.shake_unit)
        val margin = ctx.resources.getDimensionPixelSize(R.dimen.shake_margin)
        val mgr = AppWidgetManager.getInstance(ctx)
        var i = 0
        var lastDx = Int.MIN_VALUE
        val r = object : Runnable {
            override fun run() {
                if (!loop && i >= pattern.size) { manual = null; return }
                val dx = pattern[i % pattern.size] * unit
                if (dx != lastDx) {
                    val rv = RemoteViews(ctx.packageName, R.layout.widget_static)
                    rv.setViewPadding(R.id.letters, margin + dx, 0, margin - dx, 0)
                    try { mgr.partiallyUpdateAppWidget(ids, rv) } catch (e: Exception) { Log.w(TAG, "manual frame", e) }
                    lastDx = dx
                }
                i++
                main.postDelayed(this, frameMs)
            }
        }
        manual = r
        r.run()
    }
}
