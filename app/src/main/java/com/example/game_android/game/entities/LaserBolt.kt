// LaserBolt.kt
package com.example.game_android.game.entities

import android.content.Context
import android.graphics.*
import androidx.core.graphics.withSave
import com.example.game_android.R
import com.example.game_android.game.util.Strip
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

// Bullet-like laser bolt that flies toward a snapshot target (not a persistent beam)
class LaserBolt(
    sx: Float,
    sy: Float,
    tx: Float,
    ty: Float,
    ctx: Context,
    override var showHitbox: Boolean = false
) : Projectile {

    override var dead = false

    // ── Tile scaling similar to Fireball/ArmShard ────────────────────────────────
    private val tile = com.example.game_android.game.core.Constants.TILE.toFloat()
    private val widthInTilesMove = 8f

    // Projectile interface positional fields
    override var x = sx
    override var y = sy
    override var w = widthInTilesMove * tile
    override var h: Float

    // Velocity toward target
    private val speed = 15f
    private var vx: Float
    private var vy: Float

    // ── Strip / rendering plumbing (horizontal sprite sheet) ─────────────────────
    private val strip = Strip.loadStrip(
        ctx,
        R.drawable.golem_laser_beam,
        2,
        loop = true
    )
    private var frame = 0
    private var tick = 0
    private val paint = Paint().apply { isFilterBitmap = false }
    private val src = Rect()
    private val dst = RectF()
    private var angleDeg: Float = 0f
    private var dealtPlayerDamage = false

    init {
        val t0 = strip.trims[0]
        // Height from first frame aspect
        h = w * (t0.height().toFloat() / t0.width().toFloat())
        src.set(t0.left, t0.top, t0.right, t0.bottom)

        // Initial velocity toward snapshot target
        val dx = (tx - sx)
        val dy = (ty - sy)
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(0.001f)
        val nx = dx / len
        val ny = dy / len
        vx = nx * speed
        vy = ny * speed
        angleDeg = Math.toDegrees(atan2(vy, vx).toDouble()).toFloat()
    }

    override fun update(worldWidth: Float) {
        if (dead) return

        // Move forward
        x += vx; y += vy

        // Animate frames
        val s = strip
        if (++tick % s.speed == 0) {
            frame = if (s.loop) (frame + 1) % s.frames else (frame + 1).coerceAtMost(s.frames - 1)
        }

        // Maintain current angle based on velocity
        angleDeg = Math.toDegrees(atan2(vy, vx).toDouble()).toFloat()

        // Despawn off-screen only (passes through tiles/players)
        val margin = 64f
        if ((x + w) < -margin || x > worldWidth + margin || y < -margin || y > 2000f + margin) {
            dead = true
        }
    }

    // 2) Replace the debug block in draw() with this
    override fun draw(c: Canvas) {
        val t = strip.trims[frame]
        src.set(t.left, t.top, t.right, t.bottom)

        val srcW = (t.right - t.left).toFloat()
        val srcH = (t.bottom - t.top).toFloat()
        val scale = h / srcH
        val dw = srcW * scale
        val dh = srcH * scale

        val cx = x + w * 0.5f
        val cy = y + h * 0.5f
        dst.set(cx - dw * 0.5f, cy - dh * 0.5f, cx + dw * 0.5f, cy + dh * 0.5f)

        c.withSave {
            c.rotate(angleDeg, dst.centerX(), dst.centerY())
            c.drawBitmap(strip.bmp, src, dst, paint)
        }

        if (showHitbox) {
            // oriented polygon (exact hitbox)
            val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.YELLOW }
            val pts = orientedCorners()
            for (i in 0 until 4) {
                val (x1, y1) = pts[i]
                val (x2, y2) = pts[(i + 1) % 4]
                c.drawLine(x1, y1, x2, y2, p)
            }
        }
    }


    override fun bounds(): RectF {
        // Return the exact rotated rectangle bounds (same as visual sprite)
        val cx = x + w * 0.5f
        val cy = y + h * 0.5f
        val t = strip.trims[frame]
        val srcW = (t.right - t.left).toFloat()
        val srcH = (t.bottom - t.top).toFloat()
        val scale = h / srcH
        val dw = srcW * scale
        val dh = srcH * scale
        val ex = dw * 0.5f
        val ey = dh * 0.5f
        val ang = Math.toRadians(angleDeg.toDouble())
        val c = kotlin.math.cos(ang).toFloat()
        val s = kotlin.math.sin(ang).toFloat()
        fun rot(px: Float, py: Float): Pair<Float, Float> {
            val rx = px * c - py * s
            val ry = px * s + py * c
            return Pair(cx + rx, cy + ry)
        }
        val p1 = rot(-ex, -ey)
        val p2 = rot(ex, -ey)
        val p3 = rot(ex, ey)
        val p4 = rot(-ex, ey)
        val minX = kotlin.math.min(kotlin.math.min(p1.first, p2.first), kotlin.math.min(p3.first, p4.first))
        val maxX = kotlin.math.max(kotlin.math.max(p1.first, p2.first), kotlin.math.max(p3.first, p4.first))
        val minY = kotlin.math.min(kotlin.math.min(p1.second, p2.second), kotlin.math.min(p3.second, p4.second))
        val maxY = kotlin.math.max(kotlin.math.max(p1.second, p2.second), kotlin.math.max(p3.second, p4.second))
        return RectF(minX, minY, maxX, maxY)
    }

    override fun overlaps(b: PhysicsBody): Boolean {
        // Use the exact rotated rectangle for collision (same as visual sprite)
        val bb = b.bounds()
        val cx = x + w * 0.5f
        val cy = y + h * 0.5f
        val t = strip.trims[frame]
        val srcW = (t.right - t.left).toFloat()
        val srcH = (t.bottom - t.top).toFloat()
        val scale = h / srcH
        val dw = srcW * scale
        val dh = srcH * scale
        val ex = dw * 0.5f
        val ey = dh * 0.5f
        val ang = Math.toRadians(angleDeg.toDouble())
        val ux = kotlin.math.cos(ang).toFloat()
        val uy = kotlin.math.sin(ang).toFloat()
        val vxAxisX = -uy
        val vxAxisY = ux
        fun projectRectAABB(left: Float, top: Float, right: Float, bottom: Float, ax: Float, ay: Float): Pair<Float, Float> {
            val pts = arrayOf(Pair(left, top), Pair(right, top), Pair(right, bottom), Pair(left, bottom))
            var minP = Float.POSITIVE_INFINITY
            var maxP = Float.NEGATIVE_INFINITY
            for ((px, py) in pts) {
                val proj = px * ax + py * ay
                if (proj < minP) minP = proj
                if (proj > maxP) maxP = proj
            }
            return Pair(minP, maxP)
        }
        fun intervalsOverlap(a: Pair<Float, Float>, b: Pair<Float, Float>) = !(a.second < b.first || b.second < a.first)
        fun projectBolt(ax: Float, ay: Float): Pair<Float, Float> {
            val corners = arrayOf(Pair(-ex, -ey), Pair(ex, -ey), Pair(ex, ey), Pair(-ex, ey))
            var minP = Float.POSITIVE_INFINITY
            var maxP = Float.NEGATIVE_INFINITY
            for ((lx, ly) in corners) {
                val wx = cx + lx * ux + ly * vxAxisX
                val wy = cy + lx * uy + ly * vxAxisY
                val proj = wx * ax + wy * ay
                if (proj < minP) minP = proj
                if (proj > maxP) maxP = proj
            }
            return Pair(minP, maxP)
        }
        val axes = arrayOf(Pair(ux, uy), Pair(vxAxisX, vxAxisY), Pair(1f, 0f), Pair(0f, 1f))
        for ((ax, ay) in axes) {
            val boltInterval = projectBolt(ax, ay)
            val rectInterval = projectRectAABB(bb.left, bb.top, bb.right, bb.bottom, ax, ay)
            if (!intervalsOverlap(boltInterval, rectInterval)) return false
        }
        return true
    }

    // --- Damage gating like Fireball (but beam passes through) ---
    fun alreadyDamagedPlayer(): Boolean = dealtPlayerDamage
    fun markDamagedPlayer() { dealtPlayerDamage = true }

    // 1) Add this helper near the top-level methods in LaserBolt
    private fun orientedCorners(): Array<Pair<Float, Float>> {
        val cx = x + w * 0.5f
        val cy = y + h * 0.5f
        val t = strip.trims[frame]
        val srcW = (t.right - t.left).toFloat()
        val srcH = (t.bottom - t.top).toFloat()
        val scale = h / srcH
        val dw = srcW * scale
        val dh = srcH * scale
        val ex = dw * 0.5f
        val ey = dh * 0.5f

        val ang = Math.toRadians(angleDeg.toDouble())
        val ux = kotlin.math.cos(ang).toFloat()   // local +x axis in world
        val uy = kotlin.math.sin(ang).toFloat()
        val vxAxisX = -uy                         // local +y axis in world
        val vxAxisY = ux

        fun toWorld(lx: Float, ly: Float): Pair<Float, Float> {
            val wx = cx + lx * ux + ly * vxAxisX
            val wy = cy + lx * uy + ly * vxAxisY
            return Pair(wx, wy)
        }

        // TL, TR, BR, BL (consistent winding)
        return arrayOf(
            toWorld(-ex, -ey),
            toWorld( ex, -ey),
            toWorld( ex,  ey),
            toWorld(-ex,  ey),
        )
    }
}
