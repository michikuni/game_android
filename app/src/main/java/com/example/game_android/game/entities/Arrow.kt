// Arrow.kt
package com.example.game_android.game.entities

import android.graphics.*
import com.example.game_android.R
import androidx.core.graphics.withScale
import com.example.game_android.game.util.BitmapUtils
import com.example.game_android.game.util.DebugDrawUtils

class Arrow(
    override var x: Float,
    override var y: Float,
    var vx: Float,
    ctx: android.content.Context,
    override var showHitbox: Boolean = false
) : Projectile {
    override var dead = false
    private val speed = vx
    private val bmp: Bitmap =
        BitmapFactory.decodeResource(ctx.resources, R.drawable.arrow01_100x100)

    // --- find tight opaque bounds inside the bitmap (alpha != 0) ---
    private val srcTrim: Rect =
        BitmapUtils.computeOpaqueBounds(bmp) ?: Rect(0, 0, bmp.width, bmp.height)

    // Size in world units: pick width in tiles; keep aspect from the TRIMMED rect
    private val tile = com.example.game_android.game.core.Constants.TILE.toFloat()
    private val widthInTiles = 2f
    override var w = widthInTiles * tile
    override var h = w * (srcTrim.height().toFloat() / srcTrim.width().toFloat())

    private val dst = RectF()
    private val paint = Paint().apply { isFilterBitmap = false } // crisp pixel art

    // --- Debug helpers -----------------------------------------------------------

    /** The physics AABB used for collisions (world units). */
    private fun physicsBounds(): RectF = RectF(x, y, x + w, y + h)

    /** The current visual rect actually drawn for the trimmed frame (world units). */
    private fun visualBounds(): RectF = RectF(dst)

    /** Draw outlines so you can see what’s happening. Call after draw(). */
    private fun drawDebugHitbox(c: Canvas) {
        if (!showHitbox) return

        val pb = physicsBounds()
        val vb = visualBounds()
        val feetX = pb.centerX()
        val feetY = pb.bottom

        DebugDrawUtils.drawPhysicsBounds(c, pb)
        DebugDrawUtils.drawVisualBounds(c, vb)
        DebugDrawUtils.drawFeetAnchor(c, feetX, feetY)
    }

    override fun update(worldW: Float) {
        x += speed
        if (x < -64 || x > worldW + 64) dead = true
    }

    override fun draw(c: Canvas) {
        dst.set(x - 20f, y, x + w + 20f, y + h)
        if (vx >= 0f) {
            c.drawBitmap(bmp, srcTrim, dst, paint)
        } else {
            c.withScale(-1f, 1f, dst.centerX(), dst.centerY()) {
                drawBitmap(bmp, srcTrim, dst, paint)
            }
        }

        drawDebugHitbox(c)
    }

    override fun overlaps(b: PhysicsBody): Boolean {
        val bb = b.bounds()
        return dst.left < bb.right && dst.right > bb.left &&
                dst.top < bb.bottom && dst.bottom > bb.top
    }
}

