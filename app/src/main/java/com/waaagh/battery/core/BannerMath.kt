package com.waaagh.battery.core

/**
 * Battery scale of the WAAAGH! banner. Pure Kotlin, no Android types.
 *
 * Symbol i (0..6 = W A A A G H !) fills bottom-up over its own height while the
 * charge goes from 10*i to 10*i+10 %. Above 70 % the paint of all symbols turns
 * red; at 100 % everything is red.
 */
object BannerMath {
    const val SYMBOLS = 7

    fun clamp01(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x

    /** Share of symbol [i]'s height that shows paint at [level] percent. */
    fun fillFraction(level: Float, i: Int): Float = clamp01((level - 10f * i) / 10f)

    /** Share of the way from source paint to red, 0 at <=70 %, 1 at 100 %. */
    fun redFraction(level: Float): Float = clamp01((level - 70f) / 30f)
}

/** What the renderer has to draw. [allRed] = event mode (notification/call). */
data class PaintSpec(val level: Float, val allRed: Boolean) {
    fun fill(i: Int): Float = if (allRed) 1f else BannerMath.fillFraction(level, i)
    val red: Float get() = if (allRed) 1f else BannerMath.redFraction(level)
}

/**
 * Letter layer prepared from the source pixels.
 *
 * @param orig unpremultiplied ARGB of the letter layer (alpha = letter mask)
 * @param sym  symbol index per pixel (0..6), or -1 / 255 for none
 * @param tops / bottoms per-symbol vertical extent in canvas pixels; the '!'
 *             uses one range for the stroke and the dot
 */
class LetterLayer(
    val width: Int,
    val height: Int,
    val orig: IntArray,
    sym: ByteArray,
    val tops: FloatArray,
    val bottoms: FloatArray,
) {
    private val n = width * height
    val symbol = IntArray(n) { val s = sym[it].toInt() and 0xFF; if (s < BannerMath.SYMBOLS) s else -1 }

    // Per-pixel values derived from the source colours.
    private val metal = IntArray(n)     // dull dark steel, RGB
    private val hue = FloatArray(n)
    private val sat = FloatArray(n)
    private val value = FloatArray(n)
    private val paintWeight = FloatArray(n)

    init {
        val hsv = FloatArray(3)
        for (p in 0 until n) {
            val c = orig[p]
            if (c ushr 24 == 0) continue
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val steel = 0.04f + 0.32f * Math.pow(lum.toDouble(), 0.9).toFloat()
            metal[p] = Colors.pack(steel * 0.90f, steel * 1.0f, steel * 1.06f)
            Colors.rgbToHsv(r, g, b, hsv)
            hue[p] = hsv[0]; sat[p] = hsv[1]; value[p] = hsv[2]
            paintWeight[p] = BannerMath.clamp01((hsv[1] - 0.18f) / 0.30f) *
                BannerMath.clamp01((hsv[2] - 0.06f) / 0.15f)
        }
    }

    private var cachedRed = -1f
    private val paint = IntArray(n)

    /** Paint colour of every pixel at red share [t] (hue walks to red, shading kept). */
    private fun paintAt(t: Float): IntArray {
        if (t == cachedRed) return paint
        for (p in 0 until n) {
            val c = orig[p]
            if (c ushr 24 == 0) continue
            if (t == 0f) { paint[p] = c or (0xFF shl 24); continue }
            val h0 = hue[p]
            val h = if (h0 <= 180f) h0 - t * (h0 + 2f) else h0 + t * (358f - h0)
            val red = Colors.hsvToRgb(((h % 360f) + 360f) % 360f, sat[p], value[p])
            paint[p] = Colors.lerp(c, red, paintWeight[p])
        }
        cachedRed = t
        return paint
    }

    /** Render the letter layer into [out] (unpremultiplied ARGB, width*height). */
    fun render(spec: PaintSpec, out: IntArray) {
        val pnt = paintAt(spec.red)
        // fill line per symbol, in canvas rows
        val line = FloatArray(BannerMath.SYMBOLS) { i ->
            val f = spec.fill(i)
            when {
                f <= 0f -> Float.POSITIVE_INFINITY      // empty: no resampled edge row may show paint
                f >= 1f -> Float.NEGATIVE_INFINITY      // full: exactly the source pixels
                else -> bottoms[i] - f * (bottoms[i] - tops[i])
            }
        }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val p = row + x
                val c = orig[p]
                val a = c ushr 24
                if (a == 0) { out[p] = 0; continue }
                val s = symbol[p]
                // share of this pixel row below the fill line (anti-aliased edge)
                val cover = if (s < 0) 0f else BannerMath.clamp01(y + 1f - line[s])
                val col = when {
                    cover >= 1f -> pnt[p]
                    cover <= 0f -> metal[p]
                    else -> Colors.lerp(metal[p], pnt[p], cover)
                }
                out[p] = (col and 0xFFFFFF) or (a shl 24)
            }
        }
    }
}

object Colors {
    fun pack(r: Float, g: Float, b: Float): Int {
        fun ch(v: Float) = (BannerMath.clamp01(v) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    fun lerp(c0: Int, c1: Int, t: Float): Int {
        if (t <= 0f) return c0 or (0xFF shl 24)
        if (t >= 1f) return c1 or (0xFF shl 24)
        val r = ((c0 shr 16) and 0xFF) + t * (((c1 shr 16) and 0xFF) - ((c0 shr 16) and 0xFF))
        val g = ((c0 shr 8) and 0xFF) + t * (((c1 shr 8) and 0xFF) - ((c0 shr 8) and 0xFF))
        val b = (c0 and 0xFF) + t * ((c1 and 0xFF) - (c0 and 0xFF))
        return (0xFF shl 24) or ((r + 0.5f).toInt() shl 16) or ((g + 0.5f).toInt() shl 8) or (b + 0.5f).toInt()
    }

    /** h in degrees 0..360, s and v 0..1 (same convention as OpenCV float HSV). */
    fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        var h = 0f
        if (d > 1e-6f) {
            h = when (max) {
                r -> 60f * ((g - b) / d)
                g -> 60f * ((b - r) / d) + 120f
                else -> 60f * ((r - g) / d) + 240f
            }
            if (h < 0f) h += 360f
        }
        out[0] = h
        out[1] = if (max > 1e-6f) d / max else 0f
        out[2] = max
    }

    fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val hh = (h / 60f)
        val i = hh.toInt() % 6
        val f = hh - hh.toInt()
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        return when (i) {
            0 -> pack(v, t, p)
            1 -> pack(q, v, p)
            2 -> pack(p, v, t)
            3 -> pack(p, q, v)
            4 -> pack(t, p, v)
            else -> pack(v, p, q)
        }
    }
}
