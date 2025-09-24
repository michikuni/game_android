package com.example.game_android.game.util

import android.graphics.Bitmap
import android.graphics.Rect

object BitmapUtils {
    fun computeOpaqueBounds(b: Bitmap): Rect? {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)

        var left = w
        var right = -1
        var top = h
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (px[row + x] ushr 24 != 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return null // fully transparent
        return Rect(
            maxOf(0, left - 1), maxOf(0, top - 1),
            minOf(w, right + 2), minOf(h, bottom + 2)
        )
    }

    /**
     * Returns how many pixels of transparent padding are **below the lowest opaque pixel**
     * in [bmp]. If the bitmap is fully transparent, returns its full height.
     *
     * @param alphaThreshold alpha > (threshold-1) is treated as opaque; default 0 => alpha != 0
     */
    @JvmStatic
    fun computeBottomPad(bmp: Bitmap, alphaThreshold: Int = 0): Int {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        var lastOpaqueY = -1
        for (y in h - 1 downTo 0) {
            val rowOff = y * w
            var opaque = false
            for (x in 0 until w) {
                // Extract alpha and test
                if ((px[rowOff + x] ushr 24) > alphaThreshold) {
                    opaque = true
                    break
                }
            }
            if (opaque) { lastOpaqueY = y; break }
        }

        return if (lastOpaqueY == -1) h else (h - 1 - lastOpaqueY)
    }
}