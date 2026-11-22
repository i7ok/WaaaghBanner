package com.waaagh.battery.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Minimal PNG codec for tests (8-bit gray/RGB/RGBA, non-interlaced). */
object MiniPng {
    class Image(val width: Int, val height: Int, val argb: IntArray, val channels: Int)

    fun read(f: File): Image {
        val b = f.readBytes()
        var p = 8
        var w = 0; var h = 0; var colorType = 0
        val idat = ByteArrayOutputStream()
        fun u32(i: Int) = ((b[i].toInt() and 255) shl 24) or ((b[i + 1].toInt() and 255) shl 16) or
            ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255)
        while (p < b.size) {
            val len = u32(p); val type = String(b, p + 4, 4, Charsets.US_ASCII)
            val d = p + 8
            when (type) {
                "IHDR" -> {
                    w = u32(d); h = u32(d + 4)
                    require(b[d + 8].toInt() == 8 && b[d + 12].toInt() == 0) { "only 8-bit non-interlaced" }
                    colorType = b[d + 9].toInt()
                }
                "IDAT" -> idat.write(b, d, len)
                "IEND" -> break
            }
            p = d + len + 4
        }
        val ch = when (colorType) { 0 -> 1; 2 -> 3; 6 -> 4; 4 -> 2; else -> error("color type $colorType") }
        val stride = w * ch
        val raw = ByteArray((stride + 1) * h)
        Inflater().apply { setInput(idat.toByteArray()); var o = 0; while (o < raw.size) o += inflate(raw, o, raw.size - o); end() }
        val cur = ByteArray(stride); val prev = ByteArray(stride)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val ft = raw[y * (stride + 1)].toInt()
            val off = y * (stride + 1) + 1
            for (i in 0 until stride) {
                val x = raw[off + i].toInt() and 255
                val a = if (i >= ch) cur[i - ch].toInt() and 255 else 0
                val up = prev[i].toInt() and 255
                val c = if (i >= ch) prev[i - ch].toInt() and 255 else 0
                val v = when (ft) {
                    0 -> x; 1 -> x + a; 2 -> x + up; 3 -> x + ((a + up) shr 1)
                    4 -> { val pp = a + up - c; val pa = Math.abs(pp - a); val pb = Math.abs(pp - up); val pc = Math.abs(pp - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c }
                    else -> error("filter $ft")
                }
                cur[i] = v.toByte()
            }
            for (x in 0 until w) {
                val i = x * ch
                fun g(k: Int) = cur[i + k].toInt() and 255
                out[y * w + x] = when (ch) {
                    1 -> (255 shl 24) or (g(0) shl 16) or (g(0) shl 8) or g(0)
                    2 -> (g(1) shl 24) or (g(0) shl 16) or (g(0) shl 8) or g(0)
                    3 -> (255 shl 24) or (g(0) shl 16) or (g(1) shl 8) or g(2)
                    else -> (g(3) shl 24) or (g(0) shl 16) or (g(1) shl 8) or g(2)
                }
            }
            cur.copyInto(prev)
        }
        return Image(w, h, out, ch)
    }

    fun writeRgb(f: File, w: Int, h: Int, argb: IntArray) {
        val raw = ByteArray((w * 3 + 1) * h)
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0
            for (x in 0 until w) {
                val c = argb[y * w + x]
                raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte()
            }
        }
        val def = Deflater(6).apply { setInput(raw); finish() }
        val z = ByteArrayOutputStream(); val buf = ByteArray(65536)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        val out = ByteArrayOutputStream()
        val ds = DataOutputStream(out)
        ds.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            ds.writeInt(data.size)
            val t = type.toByteArray(Charsets.US_ASCII)
            ds.write(t); ds.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data); ds.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }
        chunk("IHDR", ihdr.toByteArray()); chunk("IDAT", z.toByteArray()); chunk("IEND", ByteArray(0))
        f.writeBytes(out.toByteArray())
    }
}
