package com.waaagh.battery.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.waaagh.battery.R
import com.waaagh.battery.core.BannerMeta
import com.waaagh.battery.core.LetterLayer
import com.waaagh.battery.core.PaintSpec

/**
 * Shared renderer for the widget and the test screen: turns a [PaintSpec]
 * into the letter-layer bitmap that lies on top of the static base
 * (black background + jaw, letters removed).
 */
class BannerRenderer(ctx: Context) {
    val width: Int
    val height: Int
    val base: Bitmap
    private val layer: LetterLayer
    private val buf: IntArray
    private var lastSpec: PaintSpec? = null
    private var lastBitmap: Bitmap? = null

    init {
        val am = ctx.assets
        val meta = BannerMeta.parse(am.open("banner/meta.txt").bufferedReader().readText())
        val opts = BitmapFactory.Options().apply {
            inPremultiplied = false
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val orig = am.open("banner/letters_orig.png").use { BitmapFactory.decodeStream(it, null, opts)!! }
        width = orig.width
        height = orig.height
        check(width == meta.width && height == meta.height) { "asset size mismatch" }
        val px = IntArray(width * height)
        orig.getPixels(px, 0, width, 0, 0, width, height)
        orig.recycle()

        val symBmp = am.open("banner/symbols.png").use { BitmapFactory.decodeStream(it, null, opts)!! }
        val sp = IntArray(width * height)
        symBmp.getPixels(sp, 0, width, 0, 0, width, height)
        symBmp.recycle()
        val sym = ByteArray(sp.size) { ((sp[it] shr 16) and 0xFF).toByte() }

        layer = LetterLayer(width, height, px, sym, meta.tops, meta.bottoms)
        buf = IntArray(width * height)
        base = BitmapFactory.decodeResource(ctx.resources, R.drawable.banner_base,
            BitmapFactory.Options().apply { inScaled = false })
    }

    /** Letter layer for [spec]; a fresh immutable bitmap per distinct spec. */
    @Synchronized
    fun renderLetters(spec: PaintSpec): Bitmap {
        lastBitmap?.let { if (spec == lastSpec) return it }
        layer.render(spec, buf)
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        b.setPixels(buf, 0, width, 0, 0, width, height)
        lastSpec = spec
        lastBitmap = b
        return b
    }
}
