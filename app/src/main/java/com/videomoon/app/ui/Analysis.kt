package com.videomoon.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/** プレビュー画像からヒストグラム・ゼブラ・フォーカスピーキングを作る（約4回/秒） */
object Analysis {
    class Result(val histo: IntArray, val overlay: Bitmap?)

    fun run(src: Bitmap, zebra: Boolean, zebraLevel: Int, peaking: Boolean, phase: Int): Result {
        val w = 135
        val h = (src.height * w.toFloat() / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val y = IntArray(w * h)
        val histo = IntArray(64)
        for (i in px.indices) {
            val c = px[i]
            val l = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)).toInt()
            y[i] = l
            histo[(l shr 2).coerceIn(0, 63)]++
        }
        if (!zebra && !peaking) return Result(histo, null)

        val ow = w * 2
        val oh = h * 2
        val out = IntArray(ow * oh)
        val th = zebraLevel * 255 / 100
        val zebraColor = Color.argb(200, 255, 255, 255)
        val peakColor = Color.argb(235, 80, 255, 170)
        for (yy in 0 until oh) for (xx in 0 until ow) {
            val p = (yy shr 1) * w + (xx shr 1)
            if (zebra && y[p] >= th && ((xx + yy + phase) % 8) < 3) { out[yy * ow + xx] = zebraColor; continue }
            if (peaking) {
                val sx = xx shr 1
                val sy = yy shr 1
                if (sx in 1 until w - 1 && sy in 1 until h - 1) {
                    val g = abs(y[p + 1] - y[p - 1]) + abs(y[p + w] - y[p - w])
                    if (g > 70) out[yy * ow + xx] = peakColor
                }
            }
        }
        return Result(histo, Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888))
    }
}
