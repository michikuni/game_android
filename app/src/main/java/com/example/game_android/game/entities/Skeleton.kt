// Skeleton.kt
package com.example.game_android.game.entities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.withSave
import com.example.game_android.R
import com.example.game_android.game.core.SoundManager
import com.example.game_android.game.util.BitmapUtils
import com.example.game_android.game.util.DebugDrawUtils
import com.example.game_android.game.world.GameState

class Skeleton(
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
    var hp = 10
    var alive = true
    var hasHitPlayer = false

    private val tile = com.example.game_android.game.core.Constants.TILE.toFloat()
    private val heightInTiles = 6.7f

    // AI movement tuning
    private val preferredStandOff = 120f
    private val maxSpeed = 3f
    private val accel = 0.07f
    private val friction = 0.3f

    // Firing
    private val attackCooldownTicks = 60 * 3               // 3 seconds @ 60 FPS
    private val CAST_ATTACK_FRAME = 7
    private var attackDelayCounter = 0

    // Detection / debug ranges
    private val triggerRange = 800f
    private val attackRangeX = 120f
    private val attackRangeY = 80f

    // ─────────────────────────────────────────────────────────────────────────────
    // Event listeners
    // ─────────────────────────────────────────────────────────────────────────────
    var onDeath: (() -> Unit)? = null
    var onHurt: (() -> Unit)? = null
    var onAttack: (() -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────────────────
    // Animation assets / structures
    // ─────────────────────────────────────────────────────────────────────────────
    private enum class Anim { IDLE, WALK, ATTACK, HURT, DEATH, SHIELD }

    private data class Strip(
        val bmp: Bitmap,
        val frames: Int,
        val fw: Int,
        val fh: Int,
        val speed: Int,
        val loop: Boolean,
        val trims: List<Rect>,
        val baseHpx: Int              // tallest trimmed frame in this strip (for pixel-scale mode)
    )

    private fun loadStrip(resId: Int, speed: Int, loop: Boolean = true): Strip {
        val bmp = BitmapUtils.decodePixelArt(ctx, resId)
        val fh = bmp.height
        val frames = (bmp.width / fh).coerceAtLeast(1)
        val fw = bmp.width / frames

        val trims = ArrayList<Rect>(frames)
        var maxH = 1
        for (i in 0 until frames) {
            val sx = i * fw
            val frameBmp = Bitmap.createBitmap(bmp, sx, 0, fw, fh)
            val local = BitmapUtils.computeOpaqueBounds(frameBmp) ?: Rect(0, 0, fw, fh)
            trims += Rect(sx + local.left, local.top, sx + local.right, local.bottom)
            maxH = maxOf(maxH, local.height().coerceAtLeast(1))
            frameBmp.recycle()
        }
        return Strip(bmp, frames, fw, fh, speed, loop, trims, baseHpx = maxH)
    }

    private val strips: Map<Anim, Strip> = mapOf(
        Anim.IDLE to loadStrip(R.drawable.skeleton_idle, 13, loop = true),
        Anim.WALK to loadStrip(R.drawable.skeleton_walk, 17, loop = true),
        Anim.ATTACK to loadStrip(R.drawable.skeleton_attack, 7, loop = false),
        Anim.HURT to loadStrip(R.drawable.skeleton_hurt, 20, loop = false),
        Anim.DEATH to loadStrip(R.drawable.skeleton_death, 9, loop = false),
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
    var showHitbox = false
    var debugShowRanges = false

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
    private var attackCd = 0
    private var attackingTicks = 0
    private var attackedThisAnim = false
    var dying = false
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
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────
    fun isDeadAndGone(): Boolean = deadAndGone

    fun isAttacking(): Boolean {
        return anim == Anim.ATTACK && attackingTicks > 0
    }

    fun tryAttack(playerX: Float, playerY: Float) {
        if (!alive) return
        if (attackCd > 0 || attackingTicks > 0) return

        facing = if (playerX > x + w * 0.5f) 1 else -1

        val s = strips[Anim.ATTACK]!!
        anim = Anim.ATTACK
        frame = 0; tick = 0
        attackingTicks = s.frames * s.speed
        attackCd = attackCooldownTicks
        attackedThisAnim = false
        hasHitPlayer = false
        attackDelayCounter = 0
    }

    fun hit() {
        if (deadAndGone || dying) return
        if (invulTicks > 0) return

        hp--
        attackingTicks = 0
        attackedThisAnim = true

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


    // How tight the vertical band is for vision/attack eligibility (as a fraction of Skeleton height)
    private val visionBandRatio = 0.35f

    private fun senseCenterY(): Float = y + h * 0.55f
    private fun verticalBand(band: Float): Pair<Float, Float> {
        val cy = senseCenterY()
        return (cy - band) to (cy + band)
    }

    // A forward-facing rectangle, aligned to current facing
    private fun forwardRect(reach: Float, band: Float): RectF {
        val (top, bottom) = verticalBand(band)
        val left: Float
        val right: Float
        if (facing >= 0) {
            left = x + w * 0.5f
            right = x + w * 0.5f + reach
        } else {
            left = x + w * 0.5f - reach
            right = x + w * 0.5f
        }
        return RectF(left, top, right, bottom)
    }

    // Zones that correspond 1:1 with the AI checks
    private fun visionRect(): RectF = forwardRect(triggerRange, h * visionBandRatio)

    private fun attackEligibilityRect(): RectF =
        forwardRect(attackRangeX, minOf(attackRangeY, h * visionBandRatio))

    // Public helpers so GameView can use the exact same logic
    fun canSensePlayer(target: RectF): Boolean = RectF.intersects(visionRect(), target)
    fun canStartAttackOn(target: RectF): Boolean = RectF.intersects(attackEligibilityRect(), target)


    // --- Melee hitbox (forward arc) ---
    private fun meleeHitbox(): RectF {
        // forward-facing box at torso height
        val torsoTop = y + h * 0.20f
        val torsoBot = y + h * 0.75f
        val reach = attackRangeX                    // how far forward
        val insetY = attackRangeY.coerceAtMost((torsoBot - torsoTop)) // keep vertical tidy

        val top = (torsoTop + torsoBot - insetY) * 0.5f
        val bottom = top + insetY

        val left: Float
        val right: Float

        if (facing >= 0) {
            left = x + w * 0.5f
            right = x + w * 0.5f + reach
        } else {
            left = x + w * 0.5f - reach
            right = x + w * 0.5f
        }
        return RectF(left, top, right, bottom)
    }

    fun checkMeleeHit(player: Player, sound: SoundManager, state: GameState) {
        if (!alive || !isAttacking() || hasHitPlayer) return

        val attackStrip = strips[Anim.ATTACK]!!
        val damageFrame =
            attackStrip.frames - 1 // only deal damage at the last keyframe (tweak if needed)
        if (frame < damageFrame) return

        val hitbox = meleeHitbox()

        if (RectF.intersects(hitbox, player.bounds())) {
            hasHitPlayer = true
            player.hit()
        }
    }


    // ─────────────────────────────────────────────────────────────────────────────
    // Update AI + animation
    // ─────────────────────────────────────────────────────────────────────────────
    fun updateAiAndAnim(playerX: Float, playerY: Float, onGround: Boolean) {
        if (deadAndGone) return

        if (attackCd > 0) attackCd--
        if (invulTicks > 0) invulTicks--

        val dxToPlayer = playerX - (x + w * 0.3f)
        if (kotlin.math.abs(dxToPlayer) > 2f) {
            val s = kotlin.math.sign(dxToPlayer).toInt()
            if (s != 0) facing = s
        }

// Gate for starting an attack: close horizontally, same height band, and in front
        val sameHeight = kotlin.math.abs(playerY - senseCenterY()) <= (h * visionBandRatio)
        val inFront = (dxToPlayer * facing) >= (-w * 0.10f)
        val closeEnough = kotlin.math.abs(dxToPlayer) <= attackRangeX

        if (closeEnough && sameHeight && inFront) {
            tryAttack(playerX, playerY)
        }

        when {
            dying -> vx = 0f
            anim == Anim.HURT -> vx *= 0.85f
            else -> {
                val attacking = (attackingTicks > 0)
                val wantChase = kotlin.math.abs(dxToPlayer) < triggerRange

                if (attacking) {
                    vx *= 0.70f
                } else if (wantChase) {
                    val dist = kotlin.math.abs(dxToPlayer)
                    val desiredVx = when {
                        dist > (preferredStandOff + 24f) -> maxSpeed * kotlin.math.sign(dxToPlayer)
                        dist < (preferredStandOff - 24f) -> -maxSpeed * kotlin.math.sign(dxToPlayer)
                        else -> 0f
                    }
                    if (desiredVx == 0f) vx *= friction
                    else {
                        val steer = kotlin.math.sign(desiredVx - vx)
                        vx += accel * steer
                        if (kotlin.math.abs(vx) > maxSpeed) vx = maxSpeed * kotlin.math.sign(vx)
                    }
                } else vx *= friction
            }
        }

        val moving = kotlin.math.abs(vx) > 0.05f || !onGround
        val nextAnim = when {
            dying -> Anim.DEATH
            anim == Anim.HURT -> Anim.HURT
            attackingTicks > 0 -> Anim.ATTACK
            moving -> Anim.WALK
            else -> Anim.IDLE
        }
        if (nextAnim != anim) {
            anim = nextAnim
            frame = 0; tick = 0
        }

        val strip = strips[anim]!!
        if (++tick % strip.speed == 0) {
            frame = if (strip.loop) (frame + 1) % strip.frames
            else (frame + 1).coerceAtMost(strip.frames - 1)
        }

        when (anim) {
            Anim.ATTACK -> {
                if (attackingTicks > 0) attackingTicks--
                val attackStrip = strips[Anim.ATTACK]!!
                val fireFrame = CAST_ATTACK_FRAME.coerceIn(0, attackStrip.frames - 1)
                if (!attackedThisAnim && frame >= fireFrame) {
                    onAttack?.invoke()
                    attackedThisAnim = true
                }
                if (attackingTicks == 0 && frame >= attackStrip.frames - 1) {
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
        val usePixelScale = (anim == Anim.HURT || anim == Anim.DEATH)
        val targetRefH = h
        val scale = if (!usePixelScale) targetRefH / srcHpx
        else targetRefH / strip.baseHpx.toFloat()

        val drawW = srcWpx * scale
        val drawH = srcHpx * scale
        val left = x - (drawW - w) / 2f
        val top = y + h - drawH
        dst.set(left, top, left + drawW, top + drawH)

        c.withSave {
            if (facing == -1) c.scale(-1f, 1f, dst.centerX(), dst.centerY())
            c.drawBitmap(strip.bmp, src, dst, paint)
        }

        if (showHitbox) drawDebugHitbox(c)
        if (debugShowRanges) drawDebugRanges(c)
        if (debugShowRanges && isAttacking()) c.drawRect(meleeHitbox(), meleePaint)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Debug helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private val meleePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 60, 60)
        strokeWidth = 2f
    }

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

        // Blue = vision zone (can "see" the player to begin aggro)
        val vr = visionRect()
        c.drawRect(vr, rangePaintTrigger)
        c.drawText("see", vr.left, vr.top - 6f, rangeTextPaint)

        // Orange = attack eligibility zone (may start an attack)
        val ar = attackEligibilityRect()
        c.drawRect(ar, rangePaintFire)
        c.drawText("attack", ar.left, ar.top - 6f, rangeTextPaint)
    }


    private fun physicsBounds(): RectF = RectF(x, y, x + w, y + h)
    private fun visualBounds(): RectF = RectF(dst)
}