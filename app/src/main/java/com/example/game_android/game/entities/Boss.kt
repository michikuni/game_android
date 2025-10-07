// Boss.kt
package com.example.game_android.game.entities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.withSave
import com.example.game_android.R
import com.example.game_android.game.core.Constants
import com.example.game_android.game.util.DebugDrawUtils
import com.example.game_android.game.util.SpriteDraw
import com.example.game_android.game.util.Strip
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

class Boss(
    px: Float,
    py: Float,
    private val ctx: Context
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
    // Config / Balance
    // ─────────────────────────────────────────────────────────────────────────────
    private val hpMax = 600                 // set this equal to your starting hp
    var hp = hpMax

    private var armorStacks = 0             // 0..3 (thresholds: 75%, 50%, 25%)
    val score = 10000

    var alive = true
    var armorPct = 0f                // 0..0.75 (reduced damage when buffed)
    private var armorPlayOnce = false
    private val baseTile = Constants.TILE.toFloat()
    private val heightInTiles = 20f

    private val headOffsetX = -0.5f     // relative to body center, +right
    private val headOffsetY = -0.42f   // relative to body center, -up

    private fun headX() = cx() + w * headOffsetX
    private fun headY() = cy() + h * headOffsetY

    // Movement
    private val walkMax = 2.0f
    private val chaseMax = 3.0f
    private val accel = 0.12f
    private val friction = 0.70f

    // Targeting ranges
    private val sightRadius = 9999f
    private val meleeRadius = 140f
    private val rangeRadius = 1200f

    // Action budgets / durations (ticks @60fps)
    private val cdRange = 180
    private val cdMelee = 120
    private val cdLaser = 360
    private val cdArmor = 420
    private val armorBuffDur = 240       // takes effect; visual loop while active
    private val immuneDur = 100           // short “Immune” window (e.g., spawn/phase)

    private val meleeDashMax = 10f
    private val meleeDashAccel = 0.35f
    private val meleeApproachTimeout = 90   // ~1.5s to reach the player before forcing a swing

    /** Forward arm box: size is a fraction of boss W/H; position is from body center. */
    private val armBoxHalfW = 0.18f     // 18% of w
    private val armBoxHalfH = 0.15f     // 15% of h
    private val armForwardOffsetX = 0.55f  // ~in front shoulder/hand
    private val armOffsetY = 0.3f

    /** How precisely we align the arm center with the player's center-X when dashing. */
    private val armAlignTolerancePx = 10f

    /** Damage happens exactly on this animation frame (0-based). */
    private var meleeHitFrame: Int? = null  // set in init() when strips are ready
    private var meleeHitApplied = false

    // ── Summon / Dormant state ───────────────────────────────────────────────────────
    var summoned = false           // true after the appearance completes
    private var appearing = false  // true while reverse-playing APPEAR
    private val summonImmuneTicks = 60  // brief immunity after appearing
    private val summonRadius = 420f      // used by GameView to trigger summon
    fun summonRadius() = summonRadius

    // ─────────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────────
    var onDeath: (() -> Unit)? = null
    var onHurt: (() -> Unit)? = null
    var onLaserStart: (() -> Unit)? = null
    var onLaserEnd: (() -> Unit)? = null
    var onThrowArm: (() -> Unit)? = null
    var onMelee: (() -> Unit)? = null
    var onArmorStart: (() -> Unit)? = null
    var onArmorEnd: (() -> Unit)? = null
    var onAppearanceDone: (() -> Unit)? = null

    // ── Cutscene freeze (no AI, no spawns, no movement) ─────────────────────────────
    private enum class FrozenPose { IMMUNE, IDLE }
    private var cutsceneFrozen = false
    private var cutscenePose = FrozenPose.IMMUNE
    fun setCutsceneFrozen(frozen: Boolean, poseIdle: Boolean = false) {
        cutsceneFrozen = frozen
        cutscenePose = if (poseIdle) FrozenPose.IDLE else FrozenPose.IMMUNE
    }


    // ─────────────────────────────────────────────────────────────────────────────
    // Sprites
    // ─────────────────────────────────────────────────────────────────────────────
    private enum class Anim { IDLE, GLOW, WALK, MELEE, RANGE_PREPARE, RANGE_LOOP, LASER_CHARGE, ARMOR, IMMUNE, HURT, DEATH, APPEAR }

    private val stripRange = Strip.loadStrip(ctx, R.drawable.golem_range_attack, 7, loop = false)
    private val strips = mapOf(
        Anim.IDLE to Strip.loadStrip(ctx, R.drawable.golem_idle, 9, loop = true),
        Anim.GLOW to Strip.loadStrip(
            ctx,
            R.drawable.golem_glow,
            8,
            loop = true
        ),       // “chasing” look
        Anim.WALK to Strip.loadStrip(ctx, R.drawable.golem_glow, 11, loop = true),
        Anim.MELEE to Strip.loadStrip(ctx, R.drawable.golem_melee, 6, loop = false),
        Anim.RANGE_PREPARE to stripRange,
        Anim.RANGE_LOOP to stripRange,
        Anim.LASER_CHARGE to Strip.loadStrip(ctx, R.drawable.golem_laser_cast2, 5, loop = false),
        Anim.ARMOR to Strip.loadStrip(ctx, R.drawable.golem_armor_buff, 7, loop = true),
        Anim.IMMUNE to Strip.loadStrip(ctx, R.drawable.golem_immune, 9, loop = true),
        Anim.HURT to Strip.loadStrip(ctx, R.drawable.golem_glow, 14, loop = false),
        Anim.DEATH to Strip.loadStrip(ctx, R.drawable.golem_death_appearance2, 11, loop = false),
        Anim.APPEAR to Strip.loadStrip(ctx, R.drawable.golem_death_appearance2, 12, loop = false),
    )

    // Tweak freely; physics (w/h) do NOT change.
    private val animScaleMul: Map<Anim, Float> = mapOf(
        Anim.IDLE to 1.00f,
        Anim.GLOW to 1.00f,
        Anim.WALK to 1.00f,
        Anim.MELEE to 1.00f,          // make melee read bigger
        Anim.RANGE_PREPARE to 1.00f,
        Anim.RANGE_LOOP to 1.00f,
        Anim.LASER_CHARGE to 1.2f,   // dramatic cast
        Anim.ARMOR to 1.00f,
        Anim.IMMUNE to 0.98f,         // slightly smaller
        Anim.HURT to 1.00f,
        Anim.DEATH to 1.4f,          // big finish
        Anim.APPEAR to 1.4f
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Render state
    // ─────────────────────────────────────────────────────────────────────────────
    private val paint = Paint().apply { isFilterBitmap = false }
    private val src = Rect()
    private val dst = RectF()
    private var anim = Anim.APPEAR
    private var frame = 0
    private var tick = 0
    private var facing = -1
    var debugShowHitbox = false
    var debugShowRanges = false
    var debugShowMelee = false
    private var debugMeleeHitbox: RectF? = null  // last applied hitbox (during strike)

    // ─────────────────────────────────────────────────────────────────────────────
    // State machine (one action at a time)
    // ─────────────────────────────────────────────────────────────────────────────
    private enum class Act { NONE, CHASE, MELEE, RANGE, LASER, ARMOR, IMMUNE, DYING, MELEE_APPROACH, WAIT }

    private var act = Act.IMMUNE
    private var actTicks = immuneDur

    // Cooldowns
    private var cdR = 0
    private var cdM = 0
    private var cdL = 0
    private var cdA = 0

    // RANGE sub-state
    private var rangePhaseGlow = true
    private var armWaitingToLaunch = false
    private var armTargetX = 0f
    private var armTargetY = 0f
    private var outProjectiles: MutableList<Projectile>? = null

    private val laserCastWindup = 60                 // ticks spent casting before first shot
    private val laserBurstCount = 3                  // N beams per action (tweakable)
    private val laserFirstDelay = 70
    private val laserInterDelay = 20                 // ticks between beams

    // Internal LASER-burst state
    private var laserCasting = false
    private var laserBeamsLeft = 0
    private var laserNextDelay = 0
    private var laserHoldLastCastFrame = false
    private var laserOutProjectiles: MutableList<Projectile>? = null
    // Loop the last N frames of LASER_CHARGE while firing
    private val laserLoopLen = 4
    private var laserLoopBase = 0
    private var laserLoopIdx = 0
    private var laserLoopTick = 0

    // ── Attack randomization ───────────────────────────────────────────────────────
    // higher = more often
    private var meleeWeight = 6
    private var rangeWeight = 5
    private var laserWeight = 4
    private var idleWeight = 1

    private val rng = kotlin.random.Random

    private data class Choice(val run: () -> Unit, val weight: Int)

    private fun pickWeighted(choices: List<Choice>): Choice? {
        val total = choices.sumOf { it.weight }
        if (total <= 0) return null
        var r = rng.nextInt(total)
        for (c in choices) {
            r -= c.weight
            if (r < 0) return c
        }
        return null
    }

    // ── Attack → forced idle policy ────────────────────────────────────────────────
    private val attacksBeforeIdle = 10
    private var attacksSinceRest = 0

    // Forced idle duration (a bit longer than casual WAIT)
    private val forcedIdleMinTicks = 150
    private val forcedIdleMaxTicks = 250

    // ── WAIT (loiter) tuning ──────────────────────────────────────────────────────
    private val waitMinTicks = 60
    private val waitMaxTicks = 120
    private val waitWalkSpeed = 1.2f  // gentle stroll speed

    // ─────────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────────
    init {
        val r = strips[Anim.IDLE]!!
        val aspect = r.trims[0].width().toFloat() / r.trims[0].height().toFloat()
        h = heightInTiles * baseTile
        w = h * aspect * 0.72f

        meleeHitFrame = 6
    }

    // Helpers
    private fun cx() = x + w * 0.5f
    private fun cy() = y + h * 0.55f
    private fun center() = cx() to cy()
    private fun distTo(px: Float, py: Float): Float {
        val dx = px - cx();
        val dy = py - cy()
        return sqrt(dx * dx + dy * dy)
    }

    fun isDeadAndGone(): Boolean =
        (anim == Anim.DEATH && frame == strips[Anim.DEATH]!!.frames - 1)

    // ─────────────────────────────────────────────────────────────────────────────
    // Public controls
    // ─────────────────────────────────────────────────────────────────────────────
    fun updateAiAndAnim(
        px: Float, py: Float, onGround: Boolean,
        tileSolidAtPx: (RectF) -> Boolean,
        enemyProjectiles: MutableList<Projectile>,
        playerBounds: RectF,
        onDamagePlayer: (damage: Int) -> Unit
    ) {
        // ── Summon/dormant gate ──────────────────────────────────────────────────────────
        if (!summoned) {
            // If we are currently reverse-playing APPEAR, step frames manually (reverse).
            if (appearing) {
                facing = if (px >= cx()) 1 else -1
                animTo(Anim.APPEAR) // ensure strip is correct
                val s = strips[Anim.APPEAR]!!
                // advance "reverse" on cadence
                if (++tick % s.speed == 0) {
                    frame = (frame - 1).coerceAtLeast(0)
                    if (frame == 0) {
                        // Appearance completed → become alive, short immune, hand control to AI
                        appearing = false
                        summoned = true
                        alive = true
                        startImmune(summonImmuneTicks)
                        onAppearanceDone?.invoke()
                    }
                }
                // Don’t run full AI while appearing
                return
            } else {
                // Truly dormant rock pile: show last DEATH frame, no AI.
                anim = Anim.DEATH
                frame = strips[Anim.DEATH]!!.frames - 1
                tick++
                return
            }
        }

        // Complete freeze during cutscenes (e.g., rock cinematic / roar)
        if (cutsceneFrozen) {
            vx = 0f
            animTo(if (cutscenePose == FrozenPose.IDLE) Anim.IDLE else Anim.IMMUNE)
            advanceFrames()
            return
        }

        // timers
        if (cdR > 0) cdR--
        if (cdM > 0) cdM--
        if (cdL > 0) cdL--
        if (cdA > 0) cdA--

        // choose facing (only when not busy attacking/buffed/immune/etc.)
        val dxToP = px - cx()
        val canTurnNow = (act == Act.NONE || act == Act.CHASE)
        if (canTurnNow && abs(dxToP) > 3f) {
            val dir = sign(dxToP).toInt().coerceIn(-1, 1)
            if (dir != 0) facing = dir
        }

        // action selection (only when free)
        if (act == Act.NONE || act == Act.CHASE) {
            val d = distTo(px, py)
            val canSee = d <= sightRadius
            val inRange = d <= rangeRadius

            if (canSee) {
                val options = buildList {
                    if (cdM == 0) add(
                        Choice(
                            run = { startMeleeApproach(playerBounds) },
                            weight = meleeWeight
                        )
                    )
                    if (inRange && cdR == 0) add(Choice(run = {
                        startRange(
                            enemyProjectiles,
                            px,
                            py
                        )
                    }, weight = rangeWeight))
                    if (inRange && cdL == 0) add(
                        Choice(
                            run = { startLaser(enemyProjectiles) },
                            weight = laserWeight
                        )
                    )
                    // NEW: sometimes just do nothing for a while
                    add(Choice(run = { startWait() }, weight = idleWeight))
                }
                val picked = pickWeighted(options)
                if (picked != null) picked.run.invoke() else startChase()
            } else {
                // Can't see player: sometimes wait, otherwise idle/chase
                if (rng.nextInt(100) < 50) startWait() else startIdle()
            }
        }
        // act tick
        when (act) {
            Act.MELEE_APPROACH -> {
                // Drive purely by the arm center alignment to player's center-X
                val playerCx = playerBounds.centerX()
                val dAx = armCenterDeltaXToPlayer(playerCx)

                // Move until aligned; only horizontal steering here
                val desired = meleeDashMax * sign(dAx)
                steerHoriz(desired, accelOverride = meleeDashAccel, maxOverride = meleeDashMax)

                // (nice feel) small air damping
                if (!onGround) vx *= 0.98f

                animTo(Anim.GLOW)

                // Enter melee when arm center is close enough OR timeout reached
                val aligned = kotlin.math.abs(dAx) <= armAlignTolerancePx
                if (aligned || --actTicks <= 0) {
                    vx = 0f
                    startMeleeStrike()
                }
            }

            Act.CHASE -> {
                val max = chaseMax
                val desired = max * sign(dxToP)
                steerHoriz(desired)
                // small jitter damp:
                if (!onGround) vx *= 0.98f
                // action ends opportunistically when other actions come off cd handled next frame
                animTo(if (abs(vx) > 0.05f) Anim.GLOW else Anim.IDLE)
            }

            Act.MELEE -> {
                vx *= 0.85f
                if (--actTicks <= 0) endAction()
                animTo(Anim.MELEE)

                val s = strips[Anim.MELEE]!!
                val hb = forwardArmRect()
                debugMeleeHitbox = RectF(hb)

                val hitFrame = meleeHitFrame ?: (s.frames / 2)
                if (!meleeHitApplied && frame == hitFrame) {
                    // optional: also require the *entering* of the frame:
                    if ((tick % s.speed) == 0) {
                        onMelee?.invoke()
                        if (RectF.intersects(hb, playerBounds)) onDamagePlayer(1)
                        meleeHitApplied = true
                    }
                }
            }

            Act.RANGE -> {
                // Phases:
                // 1) RANGE_PREPARE (windup)
                // 2) RANGE_LOOP: boss loops; ArmShard glows in place, then flies. Boss exits when shard is gone.
                if (rangePhaseGlow) {
                    vx *= 0.85f
                    animTo(Anim.RANGE_PREPARE)
                    if (--actTicks <= 0) {
                        rangePhaseGlow = false
                        armWaitingToLaunch = true
                        // No countdown: boss remains in loop until shard ends
                        actTicks = Int.MAX_VALUE
                        animTo(Anim.RANGE_LOOP)
                    }
                } else {
                    animTo(Anim.RANGE_LOOP)
                    val out = outProjectiles
                    if (armWaitingToLaunch && out != null) {
                        // Spawn shard at arm height near the forward hand.
                        val forwardOffsetX = w * 0.45f
                        val spawnX =
                            if (facing == 1) cx() + forwardOffsetX else cx() - forwardOffsetX
                        val spawnY = cy() - h * 0.35f
                        val speed = 15.0f

// Straight horizontal burst (no drop)
                        val fvx = speed * facing
                        val fvy = 0f

                        val proj = ArmShard(
                            spawnX, spawnY,
                            fvx, fvy,
                            ctx,
                            showHitbox = debugShowHitbox,
                            direction = facing,
                            // Supplier: when the shard’s delay ends, it will call this to snapshot the player center
                            getPlayerCenter = { playerBounds.centerX() to playerBounds.centerY() }
                        )

                        out.add(proj)
                        armWaitingToLaunch = false
                        onThrowArm?.invoke()
                    }

                    // Keep looping while any ArmShard is alive; exit when none
                    if (out != null) {
                        val anyAlive = out.any { it is ArmShard && !it.dead }
                        if (!anyAlive) endAction()
                    } else {
                        endAction()
                    }
                }
            }

            Act.LASER -> {
                // Phase A: casting animation (advance to last frame, then hold)
                if (laserCasting) {
                    animTo(Anim.LASER_CHARGE)
                    // natural anim step:
                    if (--actTicks <= 0) {
                        // move to frame hold + start the burst loop
                        laserCasting = false
                        laserHoldLastCastFrame = true
                        // Freeze at last frame:
                        frame = (strips[Anim.LASER_CHARGE]!!.frames - 1).coerceAtLeast(0)
                        tick = 0
                        // fire first immediately (or after inter-delay if you prefer)
                        laserNextDelay = laserFirstDelay
                    } else {
                        // still casting (play anim normally)
                    }
                } else {
                    // Phase B: loop last frames of cast animation and emit beams
                    animTo(Anim.LASER_CHARGE)
                    // Manual loop index advanced in advanceFrames(); here we only ensure anim

                    if (laserBeamsLeft > 0) {
                        if (laserNextDelay > 0) {
                            laserNextDelay--
                        } else {
                            // === Shoot one beam ===
                            laserOutProjectiles?.let { out ->
                                // snapshot current player center right now
                                val hx = headX()
                                val hy = headY() + 30f
                                val pxNow = playerBounds.centerX()
                                val pyNow = playerBounds.centerY()

                                // Aim at player's chest (slightly above center)
                                val chestY = pyNow - (playerBounds.height() * 0.15f)
                                val bolt = LaserBolt(
                                    sx = hx, sy = hy,
                                    tx = pxNow, ty = chestY,
                                    ctx = ctx,
                                    showHitbox = debugShowHitbox
                                )
                                out.add(bolt)
                            }

                            onLaserStart?.invoke() // optional: this now triggers per beam if you like

                            // simple “once” touch damage: let the main world loop handle projectile->player
                            // via Projectile.overlaps(); no extra work here.

                            laserBeamsLeft--
                            laserNextDelay = laserInterDelay
                        }
                    } else {
                        // All beams fired → end action
                        onLaserEnd?.invoke()
                        endAction()
                    }
                }

                // keep feet planted a bit during laser (less sliding)
                vx *= 0.80f
            }


            Act.ARMOR -> {
                vx *= 0.7f
                animTo(Anim.ARMOR)
            }

            Act.IMMUNE -> {
                vx *= 0.8f
                animTo(Anim.IMMUNE)
                if (--actTicks <= 0) endAction()
            }

            Act.DYING -> {
                vx = 0f
                animTo(Anim.DEATH)
            }

            Act.WAIT -> {
                doWait(onGround)
            }

            else -> Unit
        }

        // locomotion fallback
        if (act == Act.NONE) {
            // idle drift
            vx *= friction
            animTo(Anim.IDLE)
        }

        // frames
        advanceFrames()
    }

    // Helper: compute stacks based on current HP ratio
    private fun stacksForRatio(ratio: Float): Int = when {
        ratio <= 0.25f -> 3
        ratio <= 0.50f -> 2
        ratio <= 0.75f -> 1
        else -> 0
    }

    // Effective reduction shown by your existing armorPct (kept for UI/debug)
    private fun updateArmorPctFromStacks() {
        // damage taken multiplier = 0.75^stacks → reduction = 1 - that
        val takenMul = 0.75.pow(armorStacks.toDouble()).toFloat()
        armorPct = 1f - takenMul
    }

    private fun startArmorVisualFlash() {
        armorPlayOnce = true
        act = Act.ARMOR
        onArmorStart?.invoke()
        animTo(Anim.ARMOR)   // frame=0, tick=0
    }

    /** Put boss in dormant rock-pile state (show last DEATH frame, no AI/physics/hits). */
    fun startDormant() {
        summoned = false
        appearing = false
        alive = false                // cannot be damaged until summoned
        act = Act.NONE
        anim = Anim.DEATH           // show rock pile (last frame)
        frame = strips[Anim.DEATH]!!.frames - 1
        tick = 0
    }

    /** Trigger the reverse “appearance” playback. */
    fun triggerSummon() {
        if (summoned || appearing) return
        appearing = true
        // Play the APPEAR strip backwards: start from the last frame
        anim = Anim.APPEAR
        frame = strips[Anim.APPEAR]!!.frames - 1
        tick = 0
    }

    private enum class WaitMode { IDLE, WALK }

    private var waitMode = WaitMode.IDLE

    private fun startWait(forced: Boolean = false) {
        act = Act.WAIT
        actTicks = if (forced)
            kotlin.random.Random.nextInt(forcedIdleMinTicks, forcedIdleMaxTicks + 1)
        else
            kotlin.random.Random.nextInt(waitMinTicks, waitMaxTicks + 1)

        // Forced = true → *idle only* (no walking); otherwise randomly idle or walk
        waitMode =
            if (forced) WaitMode.IDLE else if (kotlin.random.Random.nextBoolean()) WaitMode.IDLE else WaitMode.WALK
        if (forced) vx = 0f                     // stop immediately on forced break
    }

    private fun doWait(onGround: Boolean) {
        when (waitMode) {
            WaitMode.IDLE -> {
                vx *= friction
                if (!onGround) vx *= 0.98f
                animTo(Anim.IDLE)
            }

            WaitMode.WALK -> {
                val desired = waitWalkSpeed * facing
                steerHoriz(desired)
                if (!onGround) vx *= 0.98f
                animTo(if (kotlin.math.abs(vx) > 0.05f) Anim.GLOW else Anim.IDLE)
            }
        }
        if (--actTicks <= 0) endAction()
    }

    private fun startIdle() {
        act = Act.NONE
    }

    private fun startChase() {
        act = Act.CHASE; animTo(Anim.GLOW)
    }

    /** Forward arm rect (used for attack) — single fixed box, mirrored by facing. */
    private fun forwardArmRect(): RectF {
        val hw = w * armBoxHalfW
        val hh = h * armBoxHalfH
        val ax = if (facing == 1) cx() + w * armForwardOffsetX else cx() - w * armForwardOffsetX
        val ay = cy() + h * armOffsetY
        return RectF(ax - hw, ay - hh, ax + hw, ay + hh)
    }

    /** Back arm rect (optional debug). */
    private fun backArmRect(): RectF {
        val hw = w * armBoxHalfW
        val hh = h * armBoxHalfH
        val ax = if (facing == 1) cx() - w * armForwardOffsetX else cx() + w * armForwardOffsetX
        val ay = cy() + h * armOffsetY
        return RectF(ax - hw, ay - hh, ax + hw, ay + hh)
    }

    /** X-distance from forward arm center to player's center-X. */
    private fun armCenterDeltaXToPlayer(playerCx: Float): Float {
        val f = forwardArmRect()
        val armCx = (f.left + f.right) * 0.5f
        return playerCx - armCx
    }

    private fun startMeleeApproach(playerBounds: RectF) {
        act = Act.MELEE_APPROACH
        cdM = cdMelee
        actTicks = meleeApproachTimeout

        // Lock facing toward player now (no turning during approach/attack)
        val dir = sign(playerBounds.centerX() - cx()).toInt().coerceIn(-1, 1)
        if (dir != 0) facing = dir
    }

    private fun startMeleeStrike() {
        act = Act.MELEE
        val s = strips[Anim.MELEE]!!
        actTicks = s.frames * s.speed
        meleeHitApplied = false
        animTo(Anim.MELEE)
    }


    private fun startRange(out: MutableList<Projectile>, px: Float, py: Float) {
        act = Act.RANGE
        cdR = cdRange
        rangePhaseGlow = true
        armWaitingToLaunch = false
        // Play the full RANGE_PREPARE animation: frames * speed ticks
        actTicks = (strips[Anim.RANGE_PREPARE]!!.frames * strips[Anim.RANGE_PREPARE]!!.speed)
        outProjectiles = out
        armTargetX = px; armTargetY = py
        animTo(Anim.RANGE_PREPARE)
    }

    private fun startLaser(out: MutableList<Projectile>) {
        act = Act.LASER
        cdL = cdLaser
        // begin casting
        laserCasting = true
        laserBeamsLeft = laserBurstCount
        laserNextDelay = laserFirstDelay
        laserHoldLastCastFrame = false
        actTicks = laserCastWindup
        laserOutProjectiles = out
        animTo(Anim.LASER_CHARGE)
    }


    private fun startArmor() {
        act = Act.ARMOR
        cdA = cdArmor
        armorPct = 0.6f   // 40% damage taken
        actTicks = armorBuffDur
        onArmorStart?.invoke()
        animTo(Anim.ARMOR)
    }

    private fun startImmune(ticks: Int = immuneDur) {
        act = Act.IMMUNE
        actTicks = ticks
        animTo(Anim.IMMUNE)
    }

    private fun endAction() {
        val prev = act

        // (optional) clear melee debug box when leaving melee
        if (prev == Act.MELEE) debugMeleeHitbox = null

        act = Act.NONE

        // Count only true attacks; ARMOR/IMMUNE/etc. don't count.
        if (prev == Act.MELEE || prev == Act.RANGE || prev == Act.LASER) {
            attacksSinceRest++
            if (attacksSinceRest >= attacksBeforeIdle) {
                attacksSinceRest = 0
                startWait(forced = true)   // ← enforce an idle break
                return
            }
        }
    }

    private fun steerHoriz(
        desired: Float,
        accelOverride: Float? = null,
        maxOverride: Float? = null
    ) {
        val a = accelOverride ?: accel
        val lim = maxOverride ?: (if (act == Act.CHASE) chaseMax else walkMax)
        val steer = sign(desired - vx)
        vx += a * steer
        if (abs(vx) > lim) vx = lim * sign(vx)
    }

    fun hit(dmgRaw: Int = 1) {
        if (!alive) return
        if (act == Act.IMMUNE) return

        // 1) Use current stacks to reduce THIS hit
        updateArmorPctFromStacks()
        val takenMul = 1f - armorPct           // equals 0.75^stacks
        var dmg = max(1, (dmgRaw * takenMul).roundToInt())

        Log.d("Boss", "Boss takes $dmg (raw $dmgRaw, stacks=$armorStacks, takenMul=$takenMul, armorPct=$armorPct, remaining hp=$hp/$hpMax)")
        hp -= dmg
        onHurt?.invoke()

        // 2) Check thresholds AFTER applying damage: new stacks apply to FUTURE hits
        val ratio = hp.toFloat() / hpMax.toFloat()
        val newStacks = stacksForRatio(ratio)
        if (newStacks > armorStacks) {
            armorStacks = newStacks
            updateArmorPctFromStacks()
            // (Optional) brief visual flash to communicate the new armor level
            startArmorVisualFlash()
        }

        if (hp <= 0) {
            alive = false
            act = Act.DYING
            animTo(Anim.DEATH)
            onDeath?.invoke()
        } else {
            if (act == Act.NONE || act == Act.CHASE) animTo(Anim.HURT)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Animation plumbing
    // ─────────────────────────────────────────────────────────────────────────────
    private fun animTo(a: Anim) {
        if (a == anim) return
        anim = a; frame = 0; tick = 0
    }

    private fun advanceFrames() {
        val s = strips[anim]!!

        // Play-through for ARMOR visual: ignore strip.loop and just run once
        if (anim == Anim.ARMOR && armorPlayOnce) {
            val s = strips[Anim.ARMOR]!!
            if (++tick % s.speed == 0) {
                if (frame < s.frames - 1) {
                    frame++
                } else {
                    armorPlayOnce = false
                    onArmorEnd?.invoke()
                    endAction()             // return to normal state
                }
            }
            return
        }

        // Freeze RANGE_LOOP on the final frame of RANGE_PREPARE
        if (anim == Anim.RANGE_LOOP) {
            frame = s.frames - 1
            tick++
            return
        }

        // Special handling: During LASER emission phase, loop the last 6 frames
        if (anim == Anim.LASER_CHARGE && !laserCasting && laserHoldLastCastFrame) {
            val total = s.frames
            val base = (total - laserLoopLen).coerceAtLeast(0)
            if (laserLoopBase != base) {
                laserLoopBase = base
                laserLoopIdx = 0
                laserLoopTick = 0
            }
            // Advance our mini-loop on strip speed cadence
            if (++laserLoopTick % s.speed == 0) {
                laserLoopIdx = (laserLoopIdx + 1) % laserLoopLen
            }
            frame = base + laserLoopIdx
            // Do not run default animator
            return
        }

        if (++tick % s.speed == 0) {
            frame = if (s.loop) (frame + 1) % s.frames else (frame + 1).coerceAtMost(s.frames - 1)
        }
    }


    // ─────────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────────
    fun draw(c: Canvas) {
        // ── Body sprite ────────────────────────────────────────────────────────────
        val strip = strips[anim]!!
        val trim = strip.trims[frame]
        val mul = animScaleMul[anim] ?: 1f

        val layout = SpriteDraw.layoutBottomCenter(
            baseHpx = strip.baseHpx,
            targetWorldH = h,       // boss already uses h as the visual height baseline
            mul = mul,
            x = x, y = y, w = w, h = h,
            trim = trim
        )
        src.set(layout.src)
        dst.set(layout.dst)

// draw
        SpriteDraw.draw(c, strip.bmp, layout, paint, facing)

        c.withSave {
            if (facing == -1) c.scale(-1f, 1f, dst.centerX(), dst.centerY())
            c.drawBitmap(strip.bmp, src, dst, paint)
        }

        // ── Debug: body bounds, feet, center ────────────────────────────────────────
        if (debugShowHitbox) {
            DebugDrawUtils.drawPhysicsBounds(c, RectF(x, y, x + w, y + h))
            DebugDrawUtils.drawFeetAnchor(c, dst.centerX(), y + h)
            val centerPaint = Paint().apply { color = Color.rgb(255, 140, 0); style = Paint.Style.FILL }
            c.drawCircle(dst.centerX(), dst.centerY(), 6f, centerPaint)
        }

        // ── Debug: sight/range circles ──────────────────────────────────────────────
        if (debugShowRanges) {
            val rp = Paint().apply {
                style = Paint.Style.STROKE; color = Color.CYAN; strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
            }
            val ap = Paint().apply {
                style = Paint.Style.STROKE; color = Color.MAGENTA; strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            }
            c.drawCircle(cx(), cy(), sightRadius, rp)
            c.drawCircle(cx(), cy(), rangeRadius, ap)
        }

        // ── Debug: melee hitboxes ───────────────────────────────────────────────────
        if (debugShowMelee) {
            // Predicted forward arm
            run {
                val predicted = forwardArmRect()
                val fill = Paint().apply { color = Color.argb(60, 50, 180, 255); style = Paint.Style.FILL }
                val stroke = Paint().apply { color = Color.rgb(50, 180, 255); style = Paint.Style.STROKE; strokeWidth = 2.5f }
                c.drawRect(predicted, fill); c.drawRect(predicted, stroke)
            }
            // Back arm outline
            run {
                val back = backArmRect()
                val stroke = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 1.5f }
                c.drawRect(back, stroke)
            }
            // Last applied (on hit frame)
            debugMeleeHitbox?.let { last ->
                val fill = Paint().apply { color = Color.argb(70, 255, 60, 60); style = Paint.Style.FILL }
                val stroke = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f }
                c.drawRect(last, fill); c.drawRect(last, stroke)
            }
        }
    }
}
