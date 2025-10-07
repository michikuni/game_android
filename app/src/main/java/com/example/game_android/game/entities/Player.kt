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
import com.example.game_android.game.util.SpriteDraw
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
    val damageDealtToBoss = 20

    // ─────────────────────────────────────────────────────────────────────────────
    // Event listeners
    // ─────────────────────────────────────────────────────────────────────────────
    var onHurt: (() -> Unit)? = null
    var onShootArrow: (() -> Unit)? = null
    var onMeleeStrike: ((RectF) -> Unit)? = null
    var onDeathStart: (() -> Unit)? = null    // fire when death begins
    var onDeathEnd: (() -> Unit)? = null    // fire when DIE anim finishes

    // ─────────────────────────────────────────────────────────────────────────────
    // Gameplay / Config
    // ─────────────────────────────────────────────────────────────────────────────
    val maxHp = 5
    var hp = maxHp
    private val tile = com.example.game_android.game.core.Constants.TILE.toFloat()
    private val heightInTiles = 6f

    private var dying = false
    private var deathNotified = false

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
        MeleePhase(0..8, setOf(4)),
        MeleePhase(9..17, setOf(14)),
        MeleePhase(18..27, setOf(20))
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
    private val hurtDuration = 20

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
        IDLE, WALK, ATTACK, HURT, MELEE, START_JUMP, JUMP, START_FALL, FALL, LAND, DIE
    }

    // Base strips
    private val strips: Map<Anim, Strip> = mapOf(
        Anim.IDLE to Strip.loadStrip(ctx, R.drawable.archer_idle, 2, loop = true),
        Anim.WALK to Strip.loadStrip(ctx, R.drawable.archer_run, 1, loop = true),
        Anim.ATTACK to Strip.loadStrip(ctx, R.drawable.archer_attack, 1, loop = false),
        Anim.MELEE to Strip.loadStrip(ctx, R.drawable.archer_melee2, 1, loop = false),
        Anim.HURT to Strip.loadStrip(ctx, R.drawable.archer_hurt, 2, loop = false),
        Anim.DIE to Strip.loadStrip(ctx, R.drawable.archer_death, 2, loop = false),
    )

    // Physics (w/h) do NOT change — only the rendered sprite size.
    private val animScaleMul: Map<Anim, Float> = mapOf(
        Anim.IDLE to 1.00f,
        Anim.WALK to 0.9f,
        Anim.ATTACK to 1.00f,
        Anim.MELEE to 1.1f,     // make melee read bigger
        Anim.HURT to 1.00f,
        Anim.START_JUMP to 1.00f,
        Anim.JUMP to 0.9f,
        Anim.START_FALL to 1.00f,
        Anim.FALL to 1.00f,
        Anim.LAND to 1.00f,
        Anim.DIE to 1.00f        // a bit more dramatic
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
        val baseHpx: Int,
        val variableHeight: Boolean
    ) {
        val length: Int get() = range.last - range.first + 1
        fun trimAt(localFrame: Int): Rect =
            strip.trims[range.first + localFrame.coerceIn(0, length - 1)]

        companion object {
            fun of(strip: Strip, range: IntRange, speed: Int, loop: Boolean): AnimClip {
                var maxH = 1
                var minH = Int.MAX_VALUE
                for (i in range) {
                    val r = strip.trims[i]
                    val h = (r.bottom - r.top).coerceAtLeast(1)
                    maxH = maxOf(maxH, h)
                    minH = minOf(minH, h)
                }
                val variable = (maxH - minH) > 2      // tolerance of 2px
                return AnimClip(
                    strip,
                    range,
                    speed,
                    loop,
                    baseHpx = maxH,
                    variableHeight = variable
                )
            }
        }
    }

    private val clips: Map<Anim, AnimClip> = buildMap {
        put(
            Anim.IDLE, AnimClip.of(
                strips[Anim.IDLE]!!,
                0 until strips[Anim.IDLE]!!.frames,
                strips[Anim.IDLE]!!.speed,
                true
            )
        )
        put(
            Anim.WALK, AnimClip.of(
                strips[Anim.WALK]!!,
                0 until strips[Anim.WALK]!!.frames,
                strips[Anim.WALK]!!.speed,
                true
            )
        )
        put(
            Anim.ATTACK, AnimClip.of(
                strips[Anim.ATTACK]!!,
                0 until strips[Anim.ATTACK]!!.frames,
                strips[Anim.ATTACK]!!.speed,
                false
            )
        )
        put(
            Anim.MELEE, AnimClip.of(
                strips[Anim.MELEE]!!,
                0 until strips[Anim.MELEE]!!.frames,
                strips[Anim.MELEE]!!.speed,
                false
            )
        )
        put(
            Anim.HURT, AnimClip.of(
                strips[Anim.HURT]!!,
                0 until strips[Anim.HURT]!!.frames,
                strips[Anim.HURT]!!.speed,
                false
            )
        )
        put(
            Anim.DIE, AnimClip.of(
                strips[Anim.DIE]!!,
                0 until strips[Anim.DIE]!!.frames,
                strips[Anim.DIE]!!.speed,
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

    /** Hard-cancel ranged + melee and switch to a neutral pose for cutscenes. */
    fun forceIdleForCutscene(clearHurtBlink: Boolean = true) {
        // stop any movement-driven pose changes from firing more attacks
        attackRequested = false
        meleeRequested = false

        // cancel active attack/melee state machines
        firedThisAttack = true             // ensure no arrow fires later
        meleePhaseIdx = -1
        meleeFramesHit.clear()
        lastLocalFrameSeen = -1
        lastMeleeFrameEmitted = -1

        if (clearHurtBlink) hurtTimer = 0  // optional: remove hurt flashing

        // neutral pose; don't touch canJump — we'll settle to ground in GameView
        setAnim(Anim.IDLE)
        vx = 0f
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
        if (hurtTimer > 0 || dying) return
        if (hp > 0) {
            hp--
            if (hp <= 0 && !dying) {
                dying = true
                hurtTimer = 0         // ← stop blink immediately
                vx = 0f; vy = 0f
                setAnim(Anim.DIE)
                onDeathStart?.invoke()
            } else if (!dying) {
                hurtTimer = hurtDuration
            }
        }
    }

    fun reset(px: Float, py: Float) {
        x = px; y = py; vx = 0f; vy = 0f
        hp = maxHp; canJump = false; wasJump = false
        frame = 0; tick = 0; facing = 1
        dying = false; deathNotified = false
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
        // ── Facing from velocity ─────────────────────────────────────────────────────
        if (vx > 0.05f) facing = 1 else if (vx < -0.05f) facing = -1

        // ── Ground/air flags ────────────────────────────────────────────────────────
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

        // ── State selection (animation FSM) ─────────────────────────────────────────
        if (!dying) {
            when {
                hurtTimer > 0 -> setAnim(Anim.HURT)

                // Start combo if requested
                (meleeRequested && meleeCooldownTicks == 0 && anim != Anim.MELEE) -> {
                    startMeleePhase(0)
                    meleeRequested = false
                }

                // Keep MELEE if already in it (or just requested)
                anim == Anim.MELEE || meleeRequested -> setAnim(Anim.MELEE)

                // Ranged
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
                                if (anim != Anim.LAND && anim != Anim.MELEE) {
                                    setAnim(if (movingOnGround) Anim.WALK else Anim.IDLE)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            setAnim(Anim.DIE) // keep DIE selected once dying
        }

        // ── Frame advance ───────────────────────────────────────────────────────────
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

        // ── Post-step transitions / one-shots ───────────────────────────────────────
        when (anim) {
            Anim.START_JUMP -> if (frame >= clip.length - 1) setAnim(Anim.JUMP)
            Anim.START_FALL -> if (frame >= clip.length - 1) setAnim(Anim.FALL)

            Anim.MELEE -> {
                val r = meleePhases[meleePhaseIdx].range
                val local = (frame - r.first).coerceAtLeast(0)

                // Single-fire damage frames per phase (local indices)
                if (local != lastLocalFrameSeen) {
                    lastLocalFrameSeen = local
                    val dmgSet = meleePhases[meleePhaseIdx].damageFramesLocal
                    if (local in dmgSet && local !in meleeFramesHit) {
                        val rect = buildMeleeRect()
                        onMeleeStrike?.invoke(rect)
                        if (debugShowHitbox) recordMeleeTrail(rect)
                        meleeFramesHit.add(local)
                    }
                }

                // Phase chaining or finish
                if (frame >= r.last) {
                    val nextIdx = meleePhaseIdx + 1
                    if (meleeHeld && nextIdx < meleePhases.size) {
                        startMeleePhase(nextIdx)
                    } else {
                        val moving = abs(vx) > 0.12f
                        setAnim(
                            when {
                                !canJump -> Anim.FALL
                                moving -> Anim.WALK
                                else -> Anim.IDLE
                            }
                        )
                        meleePhaseIdx = -1
                        meleeCooldownTicks = 10
                    }
                }
            }

            Anim.LAND -> if (frame >= clip.length - 1 && hurtTimer == 0) {
                val moving = abs(vx) > 0.12f
                setAnim(if (moving) Anim.WALK else Anim.IDLE)
            }

            // Loop ATTACK if held & ammo ready
            Anim.ATTACK -> if (frame >= clip.length - 1) {
                if (attackRequested && quiver.canFire()) {
                    frame = 0
                    tick = 0
                    firedThisAttack = false
                } else {
                    val moving = abs(vx) > 0.12f
                    setAnim(
                        when {
                            !grounded -> Anim.FALL
                            moving -> Anim.WALK
                            else -> Anim.IDLE
                        }
                    )
                }
            }

            Anim.DIE -> if (frame >= clip.length - 1 && !deathNotified) {
                deathNotified = true
                onDeathEnd?.invoke()
            }

            else -> {}
        }

        // ── Fire arrow on the keyframe ──────────────────────────────────────────────
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
            // keep queuedOut for continuous fire while held
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

        val effClip = clips[anim]!!
        val trim = effClip.trimAt(frame)

        val mul = animScaleMul[anim] ?: 1f
        val targetWorldH = desiredWorldH

        val layout = SpriteDraw.layoutBottomCenter(
            baseHpx = effClip.baseHpx,
            targetWorldH = targetWorldH,
            mul = mul,
            x = x, y = y, w = w, h = h,
            trim = trim
        )
        srcRect.set(layout.src)
        dstRect.set(layout.dst)

        // ── Timers & quiver ─────────────────────────────────────────────────────────
        if (hurtTimer > 0) hurtTimer--
        quiver.tick(gameState)

        // ── Draw (blink while hurt) ─────────────────────────────────────────────────
        val blink = (hurtTimer > 0) && !dying && ((hurtTimer / 2) % 2 == 0)
        c.withSave {
            if (facing == -1) c.scale(-1f, 1f, dstRect.centerX(), dstRect.centerY())
            if (!blink) c.drawBitmap(effClip.strip.bmp, srcRect, dstRect, paint)
        }

        // ── Debug ───────────────────────────────────────────────────────────────────
        if (debugShowHitbox) drawDebugHitbox(c)
        if (debugShowHitbox) drawDebugMeleeHitboxes(c)

        // ── Bookkeeping ─────────────────────────────────────────────────────────────
        wasGroundedLastFrame = grounded
        attackRequested = false
        meleeRequested = false
    }


    // ─────────────────────────────────────────────────────────────────────────────
    // Debug helpers
    // ─────────────────────────────────────────────────────────────────────────────
    // Build the melee rect for the current facing / size (same geometry you use to strike)
    private fun buildMeleeRect(): RectF {
        val reach = w * 1.37f
        val height = h * 0.6f
        val feetY = y + h
        val top = feetY - height
        return if (facing == 1) {
            val left = x + w * 0.44f
            RectF(left, top, left + reach, feetY)
        } else {
            val left = x - w * 0.8f
            RectF(left, top, left + reach, feetY)
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
