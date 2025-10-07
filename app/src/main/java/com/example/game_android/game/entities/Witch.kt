// Witch.kt
package com.example.game_android.game.entities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.withSave
import com.example.game_android.R
import com.example.game_android.game.util.DebugDrawUtils
import com.example.game_android.game.util.Strip

class Witch(
    px: Float, py: Float, private val ctx: Context
) : PhysicsBody {

    // ─────────────────────────────────────────────────────────────────────────────
    // PhysicsBody
    // ─────────────────────────────────────────────────────────────────────────────
    override var x = px
    override var y = py
    override var vx = 0f
    override var vy = 0f
    override var w = 1f
    override var h = 1f
    override var canJump = false
    override var wasJump = false

    // ─────────────────────────────────────────────────────────────────────────────
    // Gameplay / Config
    // ─────────────────────────────────────────────────────────────────────────────
    var hp = 5
    var alive = true

    private val tile = com.example.game_android.game.core.Constants.TILE.toFloat()
    private val heightInTiles = 6.5f
    val score = 150

    // AI movement tuning
    private val preferredStandOff = 260f
    private val maxSpeed = 1.9f
    private val accel = 0.12f
    private val friction = 0.7f

    // Firing
    private val fireCooldownTicks = 60 * 5                // 5 seconds @ 60 FPS
    private val CAST_FIRE_FRAME = 8                       // keyframe (0-based) to spawn projectile

    // Detection / debug ranges
    private val sightRadius = 900f          // "see" radius (big circle)
    private val attackRadius = 600f         // "shoot" radius (smaller circle)

    // Small hysteresis so she doesn't jitter on the exact boundary
    private val attackInnerBand = attackRadius * 0.85f  // if closer than this → back up
    private val attackOuterBand = attackRadius + 8f     // if farther than this → step in a bit

    // ─────────────────────────────────────────────────────────────────────────────
    // Event listeners
    // ─────────────────────────────────────────────────────────────────────────────
    var onDeath: (() -> Unit)? = null
    var onHurt: (() -> Unit)? = null
    var onThrowFireball: (() -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────────────────
    // Animation assets / structures
    // ─────────────────────────────────────────────────────────────────────────────
    private enum class Anim { IDLE, WALK, CAST, HURT, DEATH }

    private val strips: Map<Anim, Strip> = mapOf(
        Anim.IDLE to Strip.loadStrip(ctx, R.drawable.witch_idle, 13, loop = true),
        Anim.WALK to Strip.loadStrip(ctx, R.drawable.witch_walk, 11, loop = true),
        Anim.CAST to Strip.loadStrip(ctx, R.drawable.witch_attack, 7, loop = false),
        Anim.HURT to Strip.loadStrip(ctx, R.drawable.witch_hurt, 20, loop = false),
        Anim.DEATH to Strip.loadStrip(ctx, R.drawable.witch_death, 9, loop = false),
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Render state
    // ─────────────────────────────────────────────────────────────────────────────
    private val paint = Paint().apply { isFilterBitmap = false }
    private val src = Rect()
    private val dst = RectF()

    private var anim = Anim.IDLE
    private var frame = 0
    private var tick = 0
    private var facing = 1

    // Debug toggles
    var showHitbox = true
    var debugShowRanges = true

    // Range paints
    private val rangePaintTrigger = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(180, 0, 200, 255)
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
        isAntiAlias = true
    }
    private val rangePaintFire = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 120, 0)
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        isAntiAlias = true
    }
    private val rangeTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = tile * 0.45f
        isAntiAlias = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Combat / one-shot states
    // ─────────────────────────────────────────────────────────────────────────────
    private var fireCd = 0
    private var castingTicks = 0
    private var firedThisCast = false
    private var pendingOut: MutableList<Projectile>? = null
    private var pendingTargetX = 0f
    private var pendingTargetY = 0f

    private var dying = false
    private var deadAndGone = false
    private var invulTicks = 0
    private val hurtIframes = 10

    // ─────────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────────
    init {
        val ref = strips[Anim.IDLE]!!
        val aspect = ref.trims[0].width().toFloat() / ref.trims[0].height().toFloat()
        h = heightInTiles * tile
        w = h * aspect * 0.7f
    }

    // ─────────────────────────────────────────────────────────────────────────────
// Range helpers (ADD)
// ─────────────────────────────────────────────────────────────────────────────
    private fun centerX(): Float = x + w * 0.5f
    private fun centerY(): Float = y + h * 0.55f

    private fun distTo(px: Float, py: Float): Float {
        val dx = px - centerX()
        val dy = py - centerY()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun inSight(px: Float, py: Float): Boolean = distTo(px, py) <= sightRadius
    fun inAttackRange(px: Float, py: Float): Boolean = distTo(px, py) <= attackRadius

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────
    fun isDeadAndGone(): Boolean = deadAndGone

    fun tryShoot(out: MutableList<Bullet>, px: Float, py: Float) {
        // parity with Enemy.tryShoot; actual projectile is Fireball via tryShootFireball()
    }

    fun tryShootFireball(outProjectiles: MutableList<Projectile>, px: Float, py: Float) {
        if (!alive) return
        if (fireCd > 0 || castingTicks > 0) return
        if (!inAttackRange(px, py)) return  // NEW: only shoot when inside circular attack range

        val s = strips[Anim.CAST]!!
        anim = Anim.CAST
        frame = 0; tick = 0
        castingTicks = s.frames * s.speed
        fireCd = fireCooldownTicks

        firedThisCast = false
        pendingOut = outProjectiles
        pendingTargetX = px
        pendingTargetY = py
    }


    fun hit() {
        if (deadAndGone || dying) return
        if (invulTicks > 0) return

        hp--
        // Cancel cast/shot on damage
        castingTicks = 0
        pendingOut = null
        firedThisCast = true

        if (hp <= 0) {
            dying = true
            anim = Anim.DEATH
            frame = 0; tick = 0
            vx = 0f; vy = 0f
            onDeath?.invoke()
            return
        }

        onHurt?.invoke()
        invulTicks = hurtIframes
        anim = Anim.HURT
        frame = 0; tick = 0
    }

    // Priority: DEATH > HURT > CAST > WALK > IDLE
    fun updateAiAndAnim(playerX: Float, playerY: Float, onGround: Boolean) {
        if (deadAndGone) return

        // 0) Timers
        if (fireCd > 0) fireCd--
        if (invulTicks > 0) invulTicks--

        // 1) Facing
        val dxToPlayer = playerX - (x + w * 0.5f)
        if (kotlin.math.abs(dxToPlayer) > 2f) {
            val s = kotlin.math.sign(dxToPlayer).toInt()
            if (s != 0) facing = s
        }

        // 2) AI movement (state-gated)
        when {
            // Death: freeze horizontal motion
            dying -> {
                vx = 0f
            }
            // Hurt: dampen, skip decisions
            anim == Anim.HURT -> {
                vx *= 0.85f
            }

            else -> {
                val casting = (castingTicks > 0)

                if (casting) {
                    // Casting: drift a bit but mostly hold position
                    vx *= 0.70f
                } else {
                    // Decide based on circular ranges
                    val px =
                        playerX + 0.5f * 1f // player's center X (the caller already passes playerX)
                    val py =
                        playerY + 0.5f * 1f // rough center Y; playerY is top-left, this is fine

                    val cdx = playerX - centerX()
                    val absDx = kotlin.math.abs(cdx)
                    val d = distTo(playerX, playerY)

                    val desiredVx = when {
                        // Player not in sight → idle / friction
                        d > sightRadius -> 0f

                        // In sight but OUTSIDE attack → move TOWARD player along X until we enter attack band
                        d > attackOuterBand -> {
                            maxSpeed * kotlin.math.sign(cdx)
                        }

                        // Deep inside attack → back up (move AWAY) until we are just inside attack range
                        d < attackInnerBand -> {
                            -maxSpeed * kotlin.math.sign(cdx)
                        }

                        // Within the comfortable shoot band → stop (or minor drift)
                        else -> 0f
                    }

                    if (desiredVx == 0f) {
                        vx *= friction
                    } else {
                        val steer = kotlin.math.sign(desiredVx - vx)
                        vx += accel * steer
                        if (kotlin.math.abs(vx) > maxSpeed) {
                            vx = maxSpeed * kotlin.math.sign(vx)
                        }
                    }
                }
            }
        }

        // 3) Choose anim
        val moving = kotlin.math.abs(vx) > 0.05f || !onGround
        val nextAnim = when {
            dying -> Anim.DEATH
            anim == Anim.HURT -> Anim.HURT
            castingTicks > 0 -> Anim.CAST
            moving -> Anim.WALK
            else -> Anim.IDLE
        }
        if (nextAnim != anim) {
            anim = nextAnim
            frame = 0
            tick = 0
        }

        // 4) Advance frames
        val strip = strips[anim]!!
        if (++tick % strip.speed == 0) {
            frame = if (strip.loop) {
                (frame + 1) % strip.frames
            } else {
                (frame + 1).coerceAtMost(strip.frames - 1)
            }
        }

        // 5) Non-loop handling
        when (anim) {
            Anim.CAST -> {
                if (castingTicks > 0) castingTicks--

                val castStrip = strips[Anim.CAST]!!
                val fireFrame = CAST_FIRE_FRAME.coerceIn(0, castStrip.frames - 1)

                if (!firedThisCast && frame >= fireFrame) {
                    val out = pendingOut
                    if (out != null) {
                        val cx = x + w * 0.5f
                        val cy = y + h * 0.1f
                        val dx = (pendingTargetX - cx)
                        val dy = (pendingTargetY - cy)
                        val ang = kotlin.math.atan2(dy, dx)
                        val speed = 5.6f
                        val fvx = (kotlin.math.cos(ang) * speed)
                        val fvy = (kotlin.math.sin(ang) * speed)
                        out.add(Fireball(cx, cy, fvx, fvy, ctx, showHitbox))
                        onThrowFireball?.invoke()
                    }
                    firedThisCast = true
                    pendingOut = null
                }

                if (castingTicks == 0 && frame >= castStrip.frames - 1) {
                    anim = if (kotlin.math.abs(vx) > 0.05f) Anim.WALK else Anim.IDLE
                    frame = 0; tick = 0
                }
            }

            Anim.HURT -> {
                val s = strips[Anim.HURT]!!
                if (frame >= s.frames - 1) {
                    anim = if (kotlin.math.abs(vx) > 0.05f) Anim.WALK else Anim.IDLE
                    frame = 0; tick = 0
                }
            }

            Anim.DEATH -> {
                val s = strips[Anim.DEATH]!!
                if (frame >= s.frames - 1) {
                    deadAndGone = true
                }
            }

            else -> Unit
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────────
    fun draw(c: Canvas) {
        val strip = strips[anim]!!
        val trim = strip.trims[frame]
        src.set(trim.left, trim.top, trim.right, trim.bottom)

        val srcWpx = (trim.right - trim.left).toFloat()
        val srcHpx = (trim.bottom - trim.top).toFloat()

        // FIT_HEIGHT for locomotion/cast; PIXEL_SCALE for hurt/death
        val usePixelScale = (anim == Anim.HURT || anim == Anim.DEATH)
        val targetRefH = h
        val scale = if (!usePixelScale) {
            targetRefH / srcHpx
        } else {
            targetRefH / strip.baseHpx.toFloat()
        }

        val drawW = srcWpx * scale
        val drawH = srcHpx * scale

        // Bottom-align (feet anchor)
        val left = x - (drawW - w) / 2f
        val top = y + h - drawH
        dst.set(left, top, left + drawW, top + drawH)

        c.withSave {
            if (facing == -1) c.scale(-1f, 1f, dst.centerX(), dst.centerY())
            c.drawBitmap(strip.bmp, src, dst, paint)
        }

        if (showHitbox) drawDebugHitbox(c)
        if (debugShowRanges) drawDebugRanges(c)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Debug helpers
    // ─────────────────────────────────────────────────────────────────────────────
    private fun drawDebugHitbox(c: Canvas) {
        if (!showHitbox) return
        val pb = physicsBounds()
        val vb = visualBounds()
        DebugDrawUtils.drawPhysicsBounds(c, pb)
        DebugDrawUtils.drawVisualBounds(c, vb)
        DebugDrawUtils.drawFeetAnchor(c, pb.centerX(), pb.bottom)
    }

    private fun drawDebugRanges(c: Canvas) {
        if (!debugShowRanges) return

        val cx = centerX()
        val cy = centerY()

        // Sight (big, blue)
        c.drawCircle(cx, cy, sightRadius, rangePaintTrigger)
        c.drawText("sight: ${sightRadius.toInt()}",
            cx + 8f, cy - sightRadius - 8f, rangeTextPaint)

        // Attack (smaller, orange)
        c.drawCircle(cx, cy, attackRadius, rangePaintFire)
        c.drawText("attack: ${attackRadius.toInt()}",
            cx + 8f, cy - attackRadius - 8f, rangeTextPaint)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Geometry helpers
    // ─────────────────────────────────────────────────────────────────────────────
    private fun physicsBounds(): RectF = RectF(x, y, x + w, y + h)
    private fun visualBounds(): RectF = RectF(dst)
}