package com.example.game_android.game.util

import android.graphics.*
import androidx.core.graphics.withSave

/**
 * Helpers to render trimmed sprite frames with:
 * - constant per-clip/per-strip scale (baseHpx)
 * - optional per-animation multiplier (mul)
 * - bottom-aligned to physics feet, centered horizontally
 * - optional horizontal flip by 'facing'
 */
object SpriteDraw {

    data class Layout(
        val src: Rect,     // sub-rect in the spritesheet (trimmed frame)
        val dst: RectF,    // world-space destination rect
        val scale: Float   // final pixel->world scale used
    )

    /**
     * Compute layout for a trimmed frame.
     * @param baseHpx  tallest trimmed height in the current clip/strip (precomputed)
     * @param targetWorldH desired visual height in world units for this actor (e.g., h * renderScale)
     * @param mul      optional visual multiplier per animation (1.0 = baseline)
     * @param x,y,w,h  physics body rect (feet at y+h)
     * @param trim     the current frame's trimmed rect (in source bmp coords)
     */
    fun layoutBottomCenter(
        baseHpx: Int,
        targetWorldH: Float,
        mul: Float,
        x: Float, y: Float, w: Float, h: Float,
        trim: Rect
    ): Layout {
        val srcW = (trim.right - trim.left).toFloat()
        val srcH = (trim.bottom - trim.top).toFloat()

        // constant per-clip/strip scale, then per-anim multiplier
        val baseScale = targetWorldH / baseHpx.coerceAtLeast(1).toFloat()
        val scale = baseScale * mul

        val dw = srcW * scale
        val dh = srcH * scale

        val left = x - kotlin.math.abs(dw - w) / 2f
        val top  = y + h - dh

        return Layout(
            src = Rect(trim.left, trim.top, trim.right, trim.bottom),
            dst = RectF(left, top, left + dw, top + dh),
            scale = scale
        )
    }

    /**
     * Draw one frame with optional horizontal flip (facing: 1 = normal, -1 = flip).
     */
    fun draw(
        c: Canvas,
        bmp: Bitmap,
        layout: Layout,
        paint: Paint,
        facing: Int
    ) {
        c.withSave {
            if (facing == -1) c.scale(-1f, 1f, layout.dst.centerX(), layout.dst.centerY())
            c.drawBitmap(bmp, layout.src, layout.dst, paint)
        }
    }
}
