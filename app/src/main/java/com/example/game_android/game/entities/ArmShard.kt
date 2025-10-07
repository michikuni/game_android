// ArmShard.kt
package com.example.game_android.game.entities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.withSave
import com.example.game_android.R
import com.example.game_android.game.util.Strip
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ArmShard(
    px: Float,
    py: Float,
    private var vx: Float,
    private var vy: Float,
    ctx: Context,
    override var showHitbox: Boolean,
    private var direction: Int = 1, // 1=right, -1=left
    private val getPlayerCenter: (() -> Pair<Float, Float>)? = null,
) : Projectile {
    override var dead = false

    // Tile-scaled baseline like Fireball
    private val tile = com.example.game_android.game.core.Constants.TILE.toFloat()
    private val widthInTilesMove = 11f
    override var w = widthInTilesMove * tile
    override var h: Float

    override var x = if (direction > 0) px else px - w
    override var y = py

    // single strip: projectile flight only
    private val fly = Strip.loadStrip(ctx, R.drawable.golem_arm_projectile, 6, loop = true)
    private var frame = 0
    private var tick = 0
    private val paint = Paint().apply { isFilterBitmap = false }
    private val src = Rect()
    private val dst = RectF()
    private var angleDeg: Float = 0f

    private var life = 60 * 10

    // Redirect / homing logic
    private var redirectDelay = 30          // straight "tell" duration
    private var targetLocked = false        // set true when delay ends (snapshot A)
    private var targetX = px
    private var targetY = py
    private var passedTarget = false        // after reaching A, continue straight

    private val maxTurnRadPerTick = Math.toRadians(10.0).toFloat()  // curve tightness
    private val speedKeep = hypot(vx.toDouble(), vy.toDouble()).toFloat().coerceAtLeast(0.001f)
    private val arriveRadiusPx = tile * 0.8f   // consider "reached A" within ~1 tile

    // Rotation behavior
    private val SPRITE_FORWARD_DEG_OFFSET = 0f
    private var showDebug = showHitbox
    //private var showDebug = true

    init {
        // Height from first flight frame aspect
        val t0 = fly.trims[0]
        h = w * (t0.height().toFloat() / t0.width().toFloat())
        angleDeg = Math.toDegrees(kotlin.math.atan2(vy, vx).toDouble())
            .toFloat() + SPRITE_FORWARD_DEG_OFFSET
    }

    override fun update(worldWidth: Float) {
        if (dead) return

        // Phase: straight tell → lock target (A) → perpetual steer to A
        if (!targetLocked) {
            if (--redirectDelay <= 0) {
                // SNAPSHOT the player's position A now
                getPlayerCenter?.invoke()?.let { (tx, ty) ->
                    targetX = tx
                    targetY = ty
                }
                targetLocked = true
            }
        } else {
            // Steer toward locked A with capped turn rate until we pass target once
            if (!passedTarget) {
                val cx = x + w * 0.5f
                val cy = y + h * 0.5f
                val dx = targetX - cx
                val dy = targetY - cy
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                // Arrived near A? mark as passed; keep flying straight with current velocity
                if (dist <= arriveRadiusPx) {
                    passedTarget = true
                } else {
                    val desiredAng = atan2(dy, dx)
                    val curAng = atan2(vy, vx)

                    var dAng = desiredAng - curAng
                    if (dAng > Math.PI) dAng -= (2 * Math.PI).toFloat()
                    if (dAng < -Math.PI) dAng += (2 * Math.PI).toFloat()

                    val turn = dAng.coerceIn(-maxTurnRadPerTick, maxTurnRadPerTick)
                    val newAng = curAng + turn

                    // keep speed magnitude; change heading only
                    val spd = speedKeep
                    vx = cos(newAng) * spd
                    vy = sin(newAng) * spd
                }
            }
        }

        // Integrate
        x += vx; y += vy
        if (--life <= 0) dead = true

        // Update angle based on current velocity
        val sp2 = vx * vx + vy * vy
        if (sp2 > 0.001f) {
            angleDeg = Math.toDegrees(kotlin.math.atan2(vy, vx).toDouble()).toFloat() + SPRITE_FORWARD_DEG_OFFSET
        }

        // Animate frames (unchanged)
        val s = fly
        if (++tick % s.speed == 0) {
            frame = if (s.loop) (frame + 1) % s.frames else (frame + 1).coerceAtMost(s.frames - 1)
        }

        // Despawn if out of world/screen bounds (unchanged)
        val margin = 64f
        if ((x + w) < -margin || x > worldWidth + margin || y < -margin || y > 2000f + margin) {
            dead = true
        }
    }

    override fun draw(c: Canvas) {
        val s = fly
        val trim = s.trims[frame]
        src.set(trim.left, trim.top, trim.right, trim.bottom)

        val srcW = (trim.right - trim.left).toFloat()
        val srcH = (trim.bottom - trim.top).toFloat()
        val scale = h / srcH
        val dw = srcW * scale
        val dh = srcH * scale

        val cx = x + w * 0.5f
        val cy = y + h * 0.5f
        dst.set(cx - dw * 0.5f, cy - dh * 0.5f, cx + dw * 0.5f, cy + dh * 0.5f)

        c.withSave {
            c.rotate(angleDeg, dst.centerX(), dst.centerY())
            c.drawBitmap(s.bmp, src, dst, paint)
        }

        if (showDebug) {
            // draw oriented hitbox polygon (exactly what overlaps() uses)
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
        val t = fly.trims[frame]
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
        val t = fly.trims[frame]
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
        fun projectShard(ax: Float, ay: Float): Pair<Float, Float> {
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
            val shardInterval = projectShard(ax, ay)
            val rectInterval = projectRectAABB(bb.left, bb.top, bb.right, bb.bottom, ax, ay)
            if (!intervalsOverlap(shardInterval, rectInterval)) return false
        }
        return true
    }

    private fun orientedCorners(): Array<Pair<Float, Float>> {
        val cx = x + w * 0.5f
        val cy = y + h * 0.5f

        val t = fly.trims[frame]
        val srcW = (t.right - t.left).toFloat()
        val srcH = (t.bottom - t.top).toFloat()
        val scale = h / srcH
        val dw = srcW * scale
        val dh = srcH * scale
        val ex = dw * 0.5f
        val ey = dh * 0.5f

        val ang = Math.toRadians(angleDeg.toDouble())
        val ux = kotlin.math.cos(ang).toFloat() // local +x axis in world
        val uy = kotlin.math.sin(ang).toFloat()
        val vxAxisX = -uy                         // local +y axis in world
        val vxAxisY = ux

        fun toWorld(lx: Float, ly: Float): Pair<Float, Float> {
            val wx = cx + lx * ux + ly * vxAxisX
            val wy = cy + lx * uy + ly * vxAxisY
            return Pair(wx, wy)
        }

        // order: TL, TR, BR, BL (screen-space polygon winding)
        return arrayOf(
            toWorld(-ex, -ey),
            toWorld(ex, -ey),
            toWorld(ex, ey),
            toWorld(-ex, ey),
        )
    }
}