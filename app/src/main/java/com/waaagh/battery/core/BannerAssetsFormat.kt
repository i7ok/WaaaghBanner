package com.waaagh.battery.core

/** Parser for assets/banner/meta.txt: "W H" then 7 lines "name top bottom". */
class BannerMeta(val width: Int, val height: Int, val tops: FloatArray, val bottoms: FloatArray) {
    companion object {
        fun parse(text: String): BannerMeta {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val (w, h) = lines[0].split(Regex("\\s+")).map { it.toInt() }
            val tops = FloatArray(BannerMath.SYMBOLS)
            val bottoms = FloatArray(BannerMath.SYMBOLS)
            for (i in 0 until BannerMath.SYMBOLS) {
                val parts = lines[1 + i].split(Regex("\\s+"))
                tops[i] = parts[1].toFloat()
                bottoms[i] = parts[2].toFloat()
            }
            return BannerMeta(w, h, tops, bottoms)
        }
    }
}
