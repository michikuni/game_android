package com.example.game_android.game.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

data class Strip(
    val bmp: Bitmap,
    val frames: Int,
    val fw: Int,
    val fh: Int,
    val speed: Int,          // ticks per frame
    val loop: Boolean,
    val trims: List<Rect>,
    val baseHpx: Int         // max trimmed height in pixels (for baseline alignment
) {
    companion object {
        fun loadStrip(ctx: Context, resId: Int, speed: Int, loop: Boolean = true): Strip {
            val bmp = BitmapUtils.decodePixelArt(ctx, resId)
            val fh = bmp.height
            val frames = (bmp.width / fh).coerceAtLeast(1)
            val fw = bmp.width / frames

            val trims = ArrayList<Rect>(frames)
            var maxH = 1
            for (i in 0 until frames) {
                val sx = i * fw
                // Create a view for trim computation only; do NOT recycle here.
                val frameBmp = Bitmap.createBitmap(bmp, sx, 0, fw, fh)
                val local = BitmapUtils.computeOpaqueBounds(frameBmp) ?: Rect(0, 0, fw, fh)
                trims += Rect(sx + local.left, local.top, sx + local.right, local.bottom)
                maxH = maxOf(maxH, local.height().coerceAtLeast(1))
                // Avoid recycling frameBmp: it can mark shared pixel memory as recycled on some devices.
                // Let GC collect the temporary once out of scope.
            }
            return Strip(bmp, frames, fw, fh, speed, loop, trims, baseHpx = maxH)
        }
    }
}