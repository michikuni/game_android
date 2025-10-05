// Fireball.kt
package com.example.game_android.game.entities

import android.graphics.*
import androidx.core.graphics.withSave
import androidx.core.graphics.withScale
import com.example.game_android.R
import com.example.game_android.game.util.BitmapUtils
import com.example.game_android.game.util.DebugDrawUtils
import kotlin.math.max

class Fireball(
    override var x: Float,
    override var y: Float,
    private var vx: Float,
    private var vy: Float,
    ctx: android.content.Context,
    override var showHitbox: Boolean = false
) : Projectile {
    override var dead = false

    private enum class Anim { MOVE, EXPLODE }

    private data class Strip(
        val bmp: Bitmap,
        val frames: Int,
        val fw: Int,
        val fh: Int,
        val speed: Int,          // ticks per frame
        val loop: Boolean,
        val trims: List<Rect>
    )

    private fun loadStrip(ctx: android.content.Context, resId: Int, speed: Int, loop: Boolean): Strip {
        val bmp = BitmapUtils.decodePixelArt(ctx, resId)
        val fh = bmp.height
        val frames = (bmp.width / fh).coerceAtLeast(1)
        val fw = bmp.width / frames
        val trims = ArrayList<Rect>(frames)
        for (i in 0 until frames) {
            val sx = i * fw
            val frameBmp = Bitmap.createBitmap(bmp, sx, 0, fw, fh)
            val local = BitmapUtils.computeOpaqueBounds(frameBmp) ?: Rect(0, 0, fw, fh)
            trims += Rect(sx + local.left, local.top, sx + local.right, local.bottom)
            frameBmp.recycle()
        }
        return Strip(bmp, frames, fw, fh, speed, loop, trims)
    }

    // --- Strips: moving + explode ---
    private val strips: Map<Anim, Strip> = mapOf(
        Anim.MOVE    to loadStrip(ctx, R.drawable.witch_ball_moving, 2, loop = true),
        Anim.EXPLODE to loadStrip(ctx, R.drawable.witch_ball_explode, 2, loop = false)
    )

    // Physics size baseline = width in tiles for MOVE; EXPLODE will resize per frame
    private val tile = com.example.game_android.game.core.Constants.TILE.toFloat()
    private val widthInTilesMove = 4.5f
    override var w = widthInTilesMove * tile
    override var h: Float

    private val paint = Paint().apply { isFilterBitmap = false }
    private val src = Rect()
    private val dst = RectF()

    private var anim = Anim.MOVE
    private var frame = 0
    private var tick = 0

    // Damage-once flag when hitting the player
    private var dealtPlayerDamage = false

    // Directional rotation
    private val SPRITE_FORWARD_DEG_OFFSET = 0f  // set to -90f if your art points UP, 180f if LEFT, etc.
    private val ANGLE_SPEED_EPS = 0.001f        // don't jitter at near-zero speed

    private var angleDeg = 0f                   // current draw angle in degrees


    init {
        // initial height from first moving frame aspect
        val t0 = strips[Anim.MOVE]!!.trims[0]
        h = w * (t0.height().toFloat() / t0.width().toFloat())

        // Initial facing from (vx, vy)
        angleDeg = Math.toDegrees(kotlin.math.atan2(vy, vx).toDouble()).toFloat() + SPRITE_FORWARD_DEG_OFFSET
    }

    // ---------------- Debug helpers ----------------
    private fun physicsBounds(): RectF = RectF(x, y, x + w, y + h)
    private fun visualBounds(): RectF = RectF(dst)
    private fun drawDebugHitbox(c: Canvas) {
        if (!showHitbox) return
        val pb = physicsBounds()
        val vb = visualBounds()
        DebugDrawUtils.drawPhysicsBounds(c, pb)
        DebugDrawUtils.drawVisualBounds(c, vb)
        DebugDrawUtils.drawFeetAnchor(c, pb.centerX(), pb.bottom)
    }

    // ---------------- Public hooks ----------------

    /** Call when it hits something: swap to EXPLODE. If `hitPlayer`, damage should be applied once in GameView. */
    fun startExplode(hitPlayer: Boolean) {
        if (anim == Anim.EXPLODE || dead) return
        anim = Anim.EXPLODE
        frame = 0; tick = 0
        // stop movement during explosion
        vx = 0f; vy = 0f
        if (hitPlayer) dealtPlayerDamage = true
    }

    /** Check so we don’t re-apply player damage during explosion frames. */
    fun alreadyDamagedPlayer(): Boolean = dealtPlayerDamage

    /** Is currently in explosion animation? */
    fun isExploding(): Boolean = anim == Anim.EXPLODE

    // ---------------- Projectile ----------------

    override fun update(worldW: Float) {
        // Move only when flying
        if (anim == Anim.MOVE) {
            x += vx; y += vy
            val sp2 = vx * vx + vy * vy
            if (sp2 > ANGLE_SPEED_EPS) {
                angleDeg = Math.toDegrees(kotlin.math.atan2(vy, vx).toDouble()).toFloat() + SPRITE_FORWARD_DEG_OFFSET
            }
        }

        // Advance animation
        val strip = strips[anim]!!
        if (++tick % strip.speed == 0) {
            if (strip.loop) frame = (frame + 1) % strip.frames
            else frame = (frame + 1).coerceAtMost(strip.frames - 1)
        }

        // If explode strip finished → mark dead
        if (anim == Anim.EXPLODE) {
            val s = strips[Anim.EXPLODE]!!
            if (frame >= s.frames - 1 && tick % s.speed == 0) {
                dead = true
            }
        }
    }

    override fun draw(c: Canvas) {
        val strip = strips[anim]!!
        val trim = strip.trims[frame]
        src.set(trim.left, trim.top, trim.right, trim.bottom)

        // Current frame’s pixel size
        val srcW = (trim.right - trim.left).toFloat()
        val srcH = (trim.bottom - trim.top).toFloat()

        // Desired world size this frame:
        val scale =
            if (anim == Anim.MOVE) (h / srcH)                // MOVE: height == physics h
            else                   (h * 1.4f / srcH)         // EXPLODE: make bigger (feel free to tweak)

        val dw = srcW * scale
        val dh = srcH * scale

        // Center around projectile’s physics center to avoid “drift” when rotating
        val cx = x + w * 0.5f
        val cy = y + h * 0.5f
        dst.set(cx - dw * 0.5f, cy - dh * 0.5f, cx + dw * 0.5f, cy + dh * 0.5f)

        if (anim == Anim.MOVE) {
            c.withSave {
                c.rotate(angleDeg, dst.centerX(), dst.centerY())
                c.drawBitmap(strip.bmp, src, dst, paint)
            }
        } else {
            // Explosion: no rotation
            c.drawBitmap(strip.bmp, src, dst, paint)
        }

        // --- TEMP DEBUG to verify it’s drawing (remove later) ---
        if (showHitbox) {
            val outline = Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 2f
                color = Color.MAGENTA; isAntiAlias = false
            }
            c.drawRect(dst, outline)
        }
        // -------------------------------------------------------

        drawDebugHitbox(c) // keep your existing helper
    }


    override fun overlaps(b: PhysicsBody): Boolean {
        // Use current visual rect for collision to match animation size (esp. EXPLODE)
        val bb = b.bounds()
        return dst.left < bb.right && dst.right > bb.left &&
                dst.top < bb.bottom && dst.bottom > bb.top
    }
}
