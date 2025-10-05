// Player.kt
package com.example.game_android.game.entities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.withSave
import com.example.game_android.R
import com.example.game_android.game.util.DebugDrawUtils
import com.example.game_android.game.util.Strip
import com.example.game_android.game.world.GameState
import kotlin.math.abs

class Player(
    px: Float,
    py: Float,
    private val ctx: Context,
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
    // Event listeners
    // ─────────────────────────────────────────────────────────────────────────────
    var onDeath: (() -> Unit)? = null
    var onHurt: (() -> Unit)? = null
    var onShootArrow: (() -> Unit)? = null
    var onMeleeStrike: ((RectF) -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────────────────
    // Gameplay / Config
    // ─────────────────────────────────────────────────────────────────────────────
    var hp = 3
    private val tile = com.example.game_android.game.core.Constants.TILE.toFloat()
    private val heightInTiles = 6f

    // Visual scale (doesn't change collisions)
    private val renderScale = 1.2f
    private val desiredWorldH get() = h * renderScale

    // Attack
    private var attackRequested = false
    private var meleeRequested = false
    var meleeDamageFrames: Set<Int> = setOf(4, 15, 20)

    // Melee input state (updated from GameView each frame)
    private var meleeHeld: Boolean = false
    fun setMeleeHeld(held: Boolean) {
        meleeHeld = held
    }

    // ── Combo definition: N phases with (frame range within MELEE strip) + local damage frames
    data class MeleePhase(val range: IntRange, val damageFramesLocal: Set<Int>)
    var meleePhases: List<MeleePhase> = listOf(
        MeleePhase(0..8,  setOf(4)),
        MeleePhase(9..17, setOf(14)),
        MeleePhase(18..27,setOf(20))
    )

    // Current combo state
    private var meleePhaseIdx = -1
    private val meleeFramesHit = hashSetOf<Int>() // local indexes already emitted in current phase
    private var lastLocalFrameSeen = -1
    private var meleeCooldownTicks = 0
    private var lastMeleeFrameEmitted = -1
    private val ATTACK_FIRE_FRAME = 8 // keyframe to spawn arrow

    // Hurt
    private var hurtTimer = 0
    private val hurtDuration = 10

    // Quiver
    val maxAmmo = 5
    private val quiver = Quiver(maxAmmo = maxAmmo, cooldownTicks = 60)
    val ammo get() = quiver.ammo()

    // Debug
    var debugShowHitbox = false

    // ── DEBUG: melee hitbox overlay ────────────────────────────────────────────────
    private data class FadingRect(val rect: RectF, var ttl: Int)

    private val meleeTrail = ArrayDeque<FadingRect>()
    private val MELEE_TRAIL_TTL = 12          // frames to keep the trail

    private val dbgMeleeFill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 0, 200, 255)         // cyan, translucent
        isAntiAlias = false
    }
    private val dbgMeleeStroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(220, 0, 200, 255)        // cyan, strong outline
        isAntiAlias = false
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Animation assets / structures
    // ─────────────────────────────────────────────────────────────────────────────
    private enum class Anim {
        IDLE, WALK, ATTACK, HURT, MELEE,
        START_JUMP, JUMP, START_FALL, FALL, LAND
    }

    // Base strips
    private val strips: Map<Anim, Strip> = mapOf(
        Anim.IDLE to Strip.loadStrip(ctx, R.drawable.archer_idle, 2, loop = true),
        Anim.WALK to Strip.loadStrip(ctx, R.drawable.archer_run, 1, loop = true),
        Anim.ATTACK to Strip.loadStrip(ctx, R.drawable.archer_attack, 1, loop = false),
        Anim.MELEE to Strip.loadStrip(ctx, R.drawable.archer_melee2, 1, loop = false),
        Anim.HURT to Strip.loadStrip(ctx, R.drawable.archer_hurt, 2, loop = false)
    )

    // Air sequence (jump+fall+land in one strip)
    private val airStrip = Strip.loadStrip(ctx, R.drawable.archer_jump_and_fall, 1, loop = false)

    // Frame ranges inside airStrip
    private val START_JUMP_FR = 0..2
    private val JUMP_FR = 3..3
    private val START_FALL_FR = 4..5
    private val FALL_FR = 6..6
    private val LAND_FR = 7..11

    /**
     * Clip = a view into a Strip limited to [range] with its own speed/loop.
     * We also compute baseHpx = tallest trimmed frame **inside this clip** (for pixel-scale mode).
     */
    private data class AnimClip(
        val strip: Strip,
        val range: IntRange,
        val speed: Int,
        val loop: Boolean,
        val baseHpx: Int
    ) {
        val length: Int get() = range.last - range.first + 1
        fun trimAt(localFrame: Int): Rect =
            strip.trims[range.first + localFrame.coerceIn(0, length - 1)]

        companion object {
            fun of(strip: Strip, range: IntRange, speed: Int, loop: Boolean): AnimClip {
                // compute tallest frame height only within the selected range
                var maxH = 1
                for (i in range) {
                    val r = strip.trims[i]
                    val h = (r.bottom - r.top).coerceAtLeast(1)
                    if (h > maxH) maxH = h
                }
                return AnimClip(strip, range, speed, loop, baseHpx = maxH)
            }
        }
    }

    private val clips: Map<Anim, AnimClip> = buildMap {
        put(
            Anim.IDLE,
            AnimClip.of(
                strips[Anim.IDLE]!!,
                0 until strips[Anim.IDLE]!!.frames,
                strips[Anim.IDLE]!!.speed,
                true
            )
        )
        put(
            Anim.WALK,
            AnimClip.of(
                strips[Anim.WALK]!!,
                0 until strips[Anim.WALK]!!.frames,
                strips[Anim.WALK]!!.speed,
                true
            )
        )
        put(
            Anim.ATTACK,
            AnimClip.of(
                strips[Anim.ATTACK]!!,
                0 until strips[Anim.ATTACK]!!.frames,
                strips[Anim.ATTACK]!!.speed,
                false
            )
        )
        put(
            Anim.MELEE,
            AnimClip.of(
                strips[Anim.MELEE]!!,
                0 until strips[Anim.MELEE]!!.frames,
                strips[Anim.MELEE]!!.speed,
                false
            )
        )
        put(
            Anim.HURT,
            AnimClip.of(
                strips[Anim.HURT]!!,
                0 until strips[Anim.HURT]!!.frames,
                strips[Anim.HURT]!!.speed,
                false
            )
        )

        put(Anim.START_JUMP, AnimClip.of(airStrip, START_JUMP_FR, 2, loop = false))
        put(Anim.JUMP, AnimClip.of(airStrip, JUMP_FR, 2, loop = true))
        put(Anim.START_FALL, AnimClip.of(airStrip, START_FALL_FR, 2, loop = false))
        put(Anim.FALL, AnimClip.of(airStrip, FALL_FR, 2, loop = true))
        put(Anim.LAND, AnimClip.of(airStrip, LAND_FR, 2, loop = false))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Render state
    // ─────────────────────────────────────────────────────────────────────────────
    private val paint = Paint().apply {
        isFilterBitmap = false
        isDither = false
    }
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var anim = Anim.IDLE
    private var frame = 0
    private var tick = 0
    private var facing = 1

    private var wasGroundedLastFrame = true

    // ─────────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────────
    init {
        val ref = strips[Anim.IDLE]!!
        val trim0 = ref.trims[0]
        val aspect = trim0.width().toFloat() / trim0.height().toFloat()
        h = heightInTiles * tile
        w = h * aspect * 0.75f
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────
    val isAttacking: Boolean get() = (anim == Anim.ATTACK || anim == Anim.MELEE)
    fun onJumpImpulse() {
        setAnim(Anim.START_JUMP)
    }

    fun tryShoot(out: MutableList<Projectile>) {
        val dir = when {
            vx > 0.05f -> 1
            vx < -0.05f -> -1
            else -> facing
        }
        facing = dir

        // mark requested; Player.draw() will start/loop ATTACK if possible
        attackRequested = true
        queuedOut = out
        queuedDir = dir
    }

    fun tryMelee() {                                   // MELEE: request from GameView
        if (!isAttacking && meleeCooldownTicks == 0) {
            meleeRequested = true
        }
    }

    // Utility: start a new phase by index
    private fun startMeleePhase(phaseIdx: Int) {
        meleePhaseIdx = phaseIdx.coerceIn(0, meleePhases.lastIndex)
        setAnim(Anim.MELEE)                       // resets frame/tick
        val r = meleePhases[meleePhaseIdx].range
        frame = r.first                           // jump to the first frame of the phase
        tick = 0
        meleeFramesHit.clear()
        lastLocalFrameSeen = -1
    }

    fun hit() {
        if (hp > 0) {
            hp--
            hurtTimer = hurtDuration
        }
    }

    fun reset(px: Float, py: Float) {
        x = px; y = py; vx = 0f; vy = 0f
        hp = 3; canJump = false; wasJump = false
        frame = 0; tick = 0; facing = 1
        setAnim(Anim.IDLE)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Update & animation state machine
    // ─────────────────────────────────────────────────────────────────────────────
    private var queuedOut: MutableList<Projectile>? = null
    private var queuedDir = 1
    private var firedThisAttack = false

    private fun oneShotTicks(of: Anim): Int = clips[of]!!.length * clips[of]!!.speed

    private fun setAnim(next: Anim) {
        if (anim == next) return

        anim = next
        frame = 0
        tick = 0

        // Ensure a fresh shot whenever we newly enter ATTACK
        if (next == Anim.ATTACK) {
            firedThisAttack = false
        }
        if (next == Anim.MELEE) {
            // When entering via startMeleePhase we immediately set frame to phase.start
            meleeFramesHit.clear()
            lastLocalFrameSeen = -1
        }
    }


    fun draw(c: Canvas, gameState: GameState) {
        // Facing from velocity
        if (vx > 0.05f) facing = 1 else if (vx < -0.05f) facing = -1

        val grounded = canJump
        val leavingGround = !grounded && wasGroundedLastFrame
        val landedThisFrame = grounded && !wasGroundedLastFrame
        val movingOnGround = abs(vx) > 0.12f

        val ascending = vy < 0f
        val descending = !ascending

        // Is LAND currently playing and not finished?
        val landClip = clips[Anim.LAND]!!
        val landPlaying = (anim == Anim.LAND && frame < landClip.length - 1)


        if (meleeCooldownTicks > 0) meleeCooldownTicks--

        // --- Priority overrides & state selection ---
        when {
            hurtTimer > 0 -> setAnim(Anim.HURT)

            // Start combo if requested
            (meleeRequested && meleeCooldownTicks == 0 && anim != Anim.MELEE) -> {
                startMeleePhase(0)               // start Phase 1
                meleeRequested = false
            }

            // MELEE first: if already playing OR requested
            anim == Anim.MELEE || meleeRequested -> setAnim(Anim.MELEE)

            // RANGED next: if already playing OR requested + canFire
            anim == Anim.ATTACK || (attackRequested && quiver.canFire()) -> setAnim(Anim.ATTACK)
            else -> {
                // 1) WALK can interrupt LAND mid-animation if moving
                if (landPlaying && movingOnGround) {
                    setAnim(Anim.WALK)
                }
                // 2) Keep LAND if not moving
                else if (landPlaying && grounded && !movingOnGround) {
                    /* sticky LAND until finish */
                } else {
                    // 3) Normal locomotion flow
                    when {
                        landedThisFrame -> {
                            if (movingOnGround) setAnim(Anim.WALK) else setAnim(Anim.LAND)
                        }

                        !grounded -> {
                            if (leavingGround) {
                                setAnim(if (ascending) Anim.START_JUMP else Anim.START_FALL)
                            } else {
                                when (anim) {
                                    Anim.START_JUMP -> { /* wait */
                                    }

                                    Anim.JUMP -> if (descending) setAnim(Anim.START_FALL)
                                    Anim.START_FALL -> { /* wait */
                                    }

                                    Anim.FALL -> { /* loop */
                                    }

                                    Anim.LAND -> setAnim(Anim.FALL)
                                    else -> setAnim(if (ascending) Anim.JUMP else Anim.FALL)
                                }
                            }
                        }

                        else -> {
                            if (anim != Anim.LAND && anim != Anim.MELEE) {   // <── add this guard
                                setAnim(if (movingOnGround) Anim.WALK else Anim.IDLE)
                            }
                        }
                    }
                }
            }
        }

        // Advance frames in current clip
        val clip = clips[anim]!!
        if (++tick % clip.speed == 0) {
            if (anim == Anim.MELEE && meleePhaseIdx >= 0) {
                val r = meleePhases[meleePhaseIdx].range
                frame = (frame + 1).coerceAtMost(r.last)   // clamp to phase end
            } else {
                frame = if (clip.loop) (frame + 1) % clip.length
                else (frame + 1).coerceAtMost(clip.length - 1)
            }
        }

        // Post-step transitions for one-shots
        when (anim) {
            Anim.START_JUMP -> if (frame >= clip.length - 1) setAnim(Anim.JUMP)
            Anim.START_FALL -> if (frame >= clip.length - 1) setAnim(Anim.FALL)

            Anim.MELEE -> {
                val r = meleePhases[meleePhaseIdx].range
                // Emit damage on exact local frames, once each
                val local = (frame - r.first).coerceAtLeast(0)
                if (local != lastLocalFrameSeen) {
                    lastLocalFrameSeen = local
                    val dmgSet = meleePhases[meleePhaseIdx].damageFramesLocal
                    if (local in dmgSet && local !in meleeFramesHit) {
                        // Build forward hitbox (same as before; tune per phase if you want)
                        val reach  = w * 1.4f
                        val height = h * 1.0f
                        val feetY  = y + h
                        val top    = feetY - height
                        val rect = if (facing == 1) {
                            val left = x + w * 0.45f
                            RectF(left, top, left + reach, feetY)
                        } else {
                            val right = x + w * 0.55f
                            RectF(right - reach, top, right, feetY)
                        }
                        onMeleeStrike?.invoke(rect)
                        if (debugShowHitbox) recordMeleeTrail(rect)
                        meleeFramesHit.add(local)
                    }
                }

                // Phase end → either chain to next (if held) or finish combo
                if (frame >= r.last) {
                    val nextIdx = meleePhaseIdx + 1
                    if (meleeHeld && nextIdx < meleePhases.size) {
                        startMeleePhase(nextIdx)     // chain — uninterruptible next phase
                    } else {
                        // finish combo
                        val movingOnGround = kotlin.math.abs(vx) > 0.12f
                        setAnim(
                            when {
                                !canJump -> Anim.FALL
                                movingOnGround -> Anim.WALK
                                else -> Anim.IDLE
                            }
                        )
                        meleePhaseIdx = -1
                        meleeCooldownTicks = 10
                    }
                }
            }

            // LAND finishes into WALK/IDLE (no attackTimer check anymore)
            Anim.LAND -> if (frame >= clip.length - 1 && hurtTimer == 0) {
                val movingOnGround = abs(vx) > 0.12f
                setAnim(if (movingOnGround) Anim.WALK else Anim.IDLE)
            }
            // ── NEW: loop ATTACK if still held and quiver ready; else exit to locomotion
            Anim.ATTACK -> if (frame >= clip.length - 1) {
                if (attackRequested && quiver.canFire()) {
                    // restart attack animation
                    frame = 0
                    tick = 0
                    firedThisAttack = false
                    // keep anim = ATTACK
                } else {
                    // fall back to movement state
                    val grounded = canJump
                    val movingOnGround = kotlin.math.abs(vx) > 0.12f
                    setAnim(
                        when {
                            !grounded -> Anim.FALL
                            movingOnGround -> Anim.WALK
                            else -> Anim.IDLE
                        }
                    )
                }
            }

            else -> {}
        }

        if (anim == Anim.ATTACK && !firedThisAttack && frame >= ATTACK_FIRE_FRAME) {
            val out = queuedOut
            if (out != null && quiver.tryConsume(gameState)) {
                var spawnX = x + w / 2f
                if (facing == -1) spawnX -= w * 2.45f
                val spawnY = y + h * 0.23f
                val speed = 17.0f
                out.add(Arrow(spawnX, spawnY, speed * queuedDir, ctx, debugShowHitbox))
                onShootArrow?.invoke()
            }
            firedThisAttack = true
            // keep queuedOut so we can keep using same list while held; safe to keep or null it
            // queuedOut = null
        }

        // --- Frame-exact MELEE damage emission ---
// Trigger once per listed frame; cancel stops any future frames.
        if (anim == Anim.MELEE) {
            // Emit only once per frame index
            if (frame != lastMeleeFrameEmitted) {
                lastMeleeFrameEmitted = frame
                if (frame in meleeDamageFrames && frame !in meleeFramesHit) {
                    // Build a forward hitbox (tune as needed)
                    val reach = w * 0.95f
                    val height = h * 0.6f
                    val feetY = y + h
                    val top = feetY - height
                    val rect = if (facing == 1) {
                        val left = x + w * 0.45f
                        RectF(left, top, left + reach, feetY)
                    } else {
                        val right = x + w * 0.55f
                        RectF(right - reach, top, right, feetY)
                    }

                    onMeleeStrike?.invoke(rect)
                    meleeFramesHit.add(frame)
                }
            }
        }


        // Re-fetch in case setAnim changed
        val effClip = clips[anim]!!

        // Source rect from trimmed frame
        val trim = effClip.trimAt(frame)
        srcRect.set(trim.left, trim.top, trim.right, trim.bottom)

        // ── Scale mode:
        // FIT_HEIGHT for stable-height clips (IDLE/WALK/ATTACK),
        // PIXEL_SCALE for variable-height clips (air clips + HURT) using tallest frame in the clip.
        val usePixelScale = when (anim) {
            Anim.START_JUMP, Anim.JUMP, Anim.START_FALL, Anim.FALL, Anim.LAND, Anim.HURT, Anim.MELEE -> true
            else -> false
        }

        val srcWpx = (trim.right - trim.left).toFloat()
        val srcHpx = (trim.bottom - trim.top).toFloat()
        val targetRefH = desiredWorldH

        val scale = if (!usePixelScale) {
            // All frames have the same on-screen height
            targetRefH / srcHpx
        } else {
            // Constant pixel→world scale based on tallest frame in THIS CLIP
            targetRefH / effClip.baseHpx.toFloat()
        }

        val drawW = srcWpx * scale
        val drawH = srcHpx * scale

        // Bottom align (feet on ground), horizontally center around physics box
        val left = x - kotlin.math.abs(drawW - w) / 2f
        val top = y + h - drawH
        dstRect.set(left, top, left + drawW, top + drawH)

        // Timers
        if (hurtTimer > 0) hurtTimer--

        // Quiver reload
        quiver.tick(gameState)

        // Draw (blink while hurt)
        val blink = (hurtTimer > 0) && ((hurtTimer / 2) % 2 == 0)
        c.withSave {
            if (facing == -1) c.scale(-1f, 1f, dstRect.centerX(), dstRect.centerY())
            if (!blink) c.drawBitmap(effClip.strip.bmp, srcRect, dstRect, paint)
        }

        // Debug
        if (debugShowHitbox) drawDebugHitbox(c)
        if (debugShowHitbox) drawDebugMeleeHitboxes(c)

        // Remember for next frame
        wasGroundedLastFrame = grounded
        attackRequested = false
        meleeRequested = false
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Debug helpers
    // ─────────────────────────────────────────────────────────────────────────────
    // Build the melee rect for the current facing / size (same geometry you use to strike)
    private fun buildMeleeRect(): RectF {
        val reach  = w * 0.95f
        val height = h * 0.6f
        val feetY  = y + h
        val top    = feetY - height
        return if (facing == 1) {
            val left = x + w * 0.45f
            RectF(left, top, left + reach, feetY)
        } else {
            val right = x + w * 0.55f
            RectF(right - reach, top, right, feetY)
        }
    }

    private fun recordMeleeTrail(rect: RectF) {
        meleeTrail.addLast(FadingRect(RectF(rect), MELEE_TRAIL_TTL))
        // keep the queue short to avoid overdraw
        while (meleeTrail.size > 8) meleeTrail.removeFirst()
    }

    private fun drawDebugMeleeHitboxes(c: Canvas) {
        // 1) Draw current active window (if we're inside a damage frame)
        if (anim == Anim.MELEE && meleePhaseIdx >= 0) {
            val r = meleePhases[meleePhaseIdx].range
            val local = (frame - r.first).coerceAtLeast(0)
            if (local in meleePhases[meleePhaseIdx].damageFramesLocal) {
                val rect = buildMeleeRect()
                dbgMeleeFill.alpha = 64
                dbgMeleeStroke.alpha = 220
                c.drawRect(rect, dbgMeleeFill)
                c.drawRect(rect, dbgMeleeStroke)
            }
        }

        // 2) Draw fading trail of actual strikes
        val it = meleeTrail.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val t = (f.ttl / MELEE_TRAIL_TTL.toFloat()).coerceIn(0f, 1f)
            dbgMeleeFill.alpha = (64 * t).toInt().coerceIn(0, 64)
            dbgMeleeStroke.alpha = (220 * t).toInt().coerceIn(0, 220)
            c.drawRect(f.rect, dbgMeleeFill)
            c.drawRect(f.rect, dbgMeleeStroke)
            f.ttl--
            if (f.ttl <= 0) it.remove()
        }
    }

    private fun physicsBounds(): RectF = RectF(x, y, x + w, y + h)
    private fun visualBounds(): RectF = RectF(dstRect)

    private fun drawDebugHitbox(c: Canvas) {
        val pb = physicsBounds()
        val vb = visualBounds()
        val feetX = pb.centerX()
        val feetY = pb.bottom
        DebugDrawUtils.drawPhysicsBounds(c, pb)
        DebugDrawUtils.drawVisualBounds(c, vb)
        DebugDrawUtils.drawFeetAnchor(c, feetX, feetY)
    }
}
