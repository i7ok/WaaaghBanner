package com.waaagh.battery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.waaagh.battery.R
import com.waaagh.battery.core.DisplayState
import com.waaagh.battery.core.Mode
import com.waaagh.battery.core.ShakePattern

/**
 * Banner drawn with the shared renderer's bitmaps and the same shake pattern as
 * the widget (fitCenter, same side margin). Used by the test screen (2:1 strip)
 * and the cover screen ([fillParent] = take the whole view).
 */
class BannerView(ctx: Context, private val fillParent: Boolean = false) : View(ctx) {
    private var base: Bitmap? = null
    private var letters: Bitmap? = null
    private var dxUnits = 0
    private var frame = 0
    private var state: DisplayState? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val unit = resources.getDimension(R.dimen.shake_unit)
    private val margin = resources.getDimension(R.dimen.shake_margin)

    private val ticker = object : Runnable {
        override fun run() {
            val s = state ?: return
            val (pattern, ms, loop) = when (s.mode) {
                Mode.CALL -> Triple(ShakePattern.CALL, ShakePattern.CALL_FRAME_MS, true)
                Mode.NOTIFICATION -> Triple(ShakePattern.NOTIFY, ShakePattern.NOTIFY_FRAME_MS, false)
                Mode.BATTERY -> { dxUnits = 0; invalidate(); return }
            }
            if (!loop && frame >= pattern.size) { dxUnits = 0; invalidate(); return }
            dxUnits = pattern[frame % pattern.size]
            frame++
            invalidate()
            postDelayed(this, ms)
        }
    }

    fun show(base: Bitmap, letters: Bitmap, s: DisplayState) {
        this.base = base
        this.letters = letters
        val restart = state?.let { it.mode != s.mode || it.effectId != s.effectId } ?: true
        state = s
        if (restart) {
            removeCallbacks(ticker)
            frame = 0
            ticker.run()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (fillParent) setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
        else setMeasuredDimension(w, w / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = base ?: return
        val l = letters ?: return
        fit(b.width, b.height, margin, width - margin, height.toFloat())
        canvas.drawBitmap(b, null, dst, paint)
        dst.offset(dxUnits * unit, 0f)
        canvas.drawBitmap(l, null, dst, paint)
    }

    private fun fit(bw: Int, bh: Int, left: Float, right: Float, h: Float) {
        val aw = right - left
        val s = minOf(aw / bw, h / bh)
        val w = bw * s; val hh = bh * s
        val x = left + (aw - w) / 2; val y = (h - hh) / 2
        dst.set(x, y, x + w, y + hh)
    }
}
