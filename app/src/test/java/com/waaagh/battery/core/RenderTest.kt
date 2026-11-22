package com.waaagh.battery.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Renders the real assets with the shared Kotlin renderer, writes PNGs to
 * build/render-test/ and compares them with the Python reference previews.
 */
class RenderTest {
    private val assets = File("src/main/assets/banner")
    private val outDir = File("build/render-test").apply { mkdirs() }

    private fun loadLayer(): LetterLayer {
        val meta = BannerMeta.parse(File(assets, "meta.txt").readText())
        val img = MiniPng.read(File(assets, "letters_orig.png"))
        val w = img.width; val h = img.height
        val orig = img.argb
        val symImg = MiniPng.read(File(assets, "symbols.png"))
        val sym = ByteArray(w * h) { ((symImg.argb[it] shr 16) and 255).toByte() }
        return LetterLayer(w, h, orig, sym, meta.tops, meta.bottoms)
    }

    private fun compose(layer: LetterLayer, letters: IntArray): IntArray {
        val out = MiniPng.read(File("src/main/res/drawable-nodpi/banner_base.png")).argb.copyOf()
        for (p in out.indices) {
            val c = letters[p]; val a = (c ushr 24) / 255f
            if (a <= 0f) continue
            out[p] = Colors.lerp(out[p], c, a)
        }
        return out
    }

    private fun save(name: String, w: Int, h: Int, px: IntArray) {
        MiniPng.writeRgb(File(outDir, name), w, h, px)
    }

    @Test fun renderCheckpointsMatchReference() {
        val layer = loadLayer()
        val w = layer.width; val h = layer.height
        val letters = IntArray(w * h)
        for (lv in listOf(0, 5, 35, 65, 70, 85, 100)) {
            val t0 = System.nanoTime()
            layer.render(PaintSpec(lv.toFloat(), false), letters)
            val ms = (System.nanoTime() - t0) / 1e6
            val full = compose(layer, letters)
            save("kotlin_%03d.png".format(lv), w, h, full)
            val ref = File("../work/preview_%03d.png".format(lv))
            if (ref.exists()) {
                val rp = MiniPng.read(ref).argb
                var sum = 0L
                for (p in rp.indices) for (sh in intArrayOf(0, 8, 16))
                    sum += abs(((rp[p] shr sh) and 255) - ((full[p] shr sh) and 255))
                val mad = sum.toDouble() / (rp.size * 3)
                println("level $lv: render %.1f ms, mean abs diff vs python %.3f".format(ms, mad))
                assertTrue("level $lv differs from reference: $mad", mad < 1.5)
            }
        }
        layer.render(PaintSpec(30f, true), letters)
        save("kotlin_event_red.png", w, h, compose(layer, letters))
    }

    /** At 70 % the banner must equal the source picture (letters untouched). */
    @Test fun seventyEqualsSource() {
        val layer = loadLayer()
        val out = IntArray(layer.width * layer.height)
        layer.render(PaintSpec(70f, false), out)
        var maxd = 0
        for (p in out.indices) {
            val a = layer.orig[p] ushr 24
            if (a == 0) continue
            for (sh in intArrayOf(0, 8, 16))
                maxd = maxOf(maxd, abs(((out[p] shr sh) and 255) - ((layer.orig[p] shr sh) and 255)))
        }
        assertTrue("max channel diff $maxd", maxd == 0)
    }
}
