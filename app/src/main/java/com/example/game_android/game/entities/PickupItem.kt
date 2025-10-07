// PickupItem.kt
package com.example.game_android.game.entities

import android.content.Context
import android.graphics.*
import androidx.compose.ui.graphics.withSave
import androidx.core.graphics.scale
import androidx.core.graphics.withSave
import com.example.game_android.R
import com.example.game_android.game.core.Constants
import kotlin.math.sin

class PickupItem(
    val type: Type,
    sx: Float,
    sy: Float,
    ctx: Context
) {
    enum class Type { HEART, ARROWS }

    private val tile = Constants.TILE.toFloat()

    private val bmp: Bitmap = when (type) {
        Type.HEART  -> BitmapFactory.decodeResource(ctx.resources, R.drawable.health_potion)
        Type.ARROWS -> BitmapFactory.decodeResource(ctx.resources, R.drawable.archer_arrow)
    }

    // scale sprite by height ~0.9 tile, preserve aspect
    private val aspect = bmp.width / bmp.height.toFloat()
    private val h = if(type == Type.HEART) tile * 4f else tile * 7f
    private val w = h * aspect

    // place centered within the tile cell; bob around this base
    private val baseX = sx + (tile - w) * 0.5f
    private val baseY = sy - h * 0.2f

    private val paint = Paint().apply { isFilterBitmap = true; isDither = true }

    private var t = 0f
    private val bobAmp = tile * 0.15f
    private val bobSpeed = 0.10f

    fun update() { t += bobSpeed }

    fun bounds(): RectF {
        val yOff = (sin(t.toDouble()) * bobAmp).toFloat()
        return RectF(baseX, baseY + yOff, baseX + w, baseY + yOff + h)
    }

    fun draw(c: Canvas) {
        val yOff = (sin(t.toDouble()) * bobAmp).toFloat()
        val dst = RectF(baseX, baseY + yOff, baseX + w, baseY + yOff + h)

        if (type == Type.ARROWS) {
            // Rotate 45° upward (counter-clockwise in Android coords)
            c.withSave {
                rotate(-45f, dst.centerX(), dst.centerY())
                c.drawBitmap(bmp, null, dst, paint)
            }
        } else {
            c.drawBitmap(bmp, null, dst, paint)
        }
    }
}
