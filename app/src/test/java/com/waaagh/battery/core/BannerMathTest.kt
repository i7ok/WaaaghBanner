package com.waaagh.battery.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BannerMathTest {
    private fun fills(level: Int) = (0 until 7).map { BannerMath.fillFraction(level.toFloat(), it) }

    @Test fun zeroIsAllSteel() = assertEquals(List(7) { 0f }, fills(0))

    @Test fun fivePercentHalfW() {
        val f = fills(5)
        assertEquals(0.5f, f[0], 1e-6f)
        assertEquals(0f, f[1], 1e-6f)
    }

    @Test fun thirtyFiveHalfThirdA() {
        val f = fills(35)
        assertEquals(listOf(1f, 1f, 1f, 0.5f, 0f, 0f, 0f), f)
    }

    @Test fun sixtyFiveHalfBang() = assertEquals(0.5f, fills(65)[6], 1e-6f)

    @Test fun rangeEdgesBothWays() {
        for (i in 0 until 7) {
            assertEquals(0f, BannerMath.fillFraction(10f * i, i), 1e-6f)
            assertEquals(1f, BannerMath.fillFraction(10f * i + 10f, i), 1e-6f)
            assertEquals(1f, BannerMath.fillFraction(10f * i + 10f, i), 1e-6f)
        }
    }

    @Test fun seventyIsSourceNoRed() {
        assertEquals(List(7) { 1f }, fills(70))
        assertEquals(0f, BannerMath.redFraction(70f), 1e-6f)
    }

    @Test fun redScale() {
        assertEquals(0.5f, BannerMath.redFraction(85f), 1e-6f)
        assertEquals(1f, BannerMath.redFraction(100f), 1e-6f)
    }

    @Test fun eventSpecIsAllRedFull() {
        val s = PaintSpec(3f, allRed = true)
        assertEquals(1f, s.red, 0f)
        for (i in 0 until 7) assertEquals(1f, s.fill(i), 0f)
    }

    @Test fun hsvRoundTrip() {
        val hsv = FloatArray(3)
        for (c in listOf(0xFF20C030.toInt(), 0xFFE0A010.toInt(), 0xFFD01818.toInt(), 0xFF808080.toInt())) {
            Colors.rgbToHsv(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, hsv)
            assertEquals(c, Colors.hsvToRgb(hsv[0], hsv[1], hsv[2]))
        }
    }
}
