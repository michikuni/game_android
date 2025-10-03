// Player.kt
package com.example.game_android.game.entities

import android.content.Context
import android.graphics.*
import com.example.game_android.R
import androidx.core.graphics.withSave
import com.example.game_android.game.util.BitmapUtils
import com.example.game_android.game.util.DebugDrawUtils
import com.example.game_android.game.world.GameState
import kotlin.math.abs

class Player(
    px: Float, py: Float, private var ctx: Context
) : PhysicsBody {

    override var x = px
    override var y = py
    override var vx = 0f
    override var vy = 0f
    override var w = 1f
    override var h = 1f
    private val tile = com.example.game_android.game.core.Constants.TILE.toFloat()
    private val heightInTiles = 4f
    override var canJump = false
    override var wasJump = false

    var hp = 3
    private var cd = 0

    private enum class Anim { IDLE, WALK, ATTACK, HURT }

    // --- Debug helpers -----------------------------------------------------------

    // Toggle from anywhere (or expose a setter)
    var debugShowHitbox = false

    /** The physics AABB used for collisions (world units). */
    private fun physicsBounds(): RectF = RectF(x, y, x + w, y + h)

    /** The current visual rect actually drawn for the trimmed frame (world units). */
    private fun visualBounds(): RectF = RectF(dstRect)

    /** Draw outlines so you can see what’s happening. Call after draw(). */
    private fun drawDebugHitbox(c: Canvas) {
        if (!debugShowHitbox) return

        val pb = physicsBounds()
        val vb = visualBounds()
        val feetX = pb.centerX()
        val feetY = pb.bottom

        DebugDrawUtils.drawPhysicsBounds(c, pb)
        DebugDrawUtils.drawVisualBounds(c, vb)
        DebugDrawUtils.drawFeetAnchor(c, feetX, feetY)
    }

    private data class Strip(
        val bmp: Bitmap,
        val frames: Int,
        val fw: Int,
        val fh: Int,
        val speed: Int,
        val loop: Boolean,
        // per-frame tight source rects (relative to the strip, not 0..fw)
        val trims: List<Rect>
    )

    private fun loadStrip(resId: Int, speed: Int, loop: Boolean = true): Strip {
        val bmp = BitmapUtils.decodePixelArt(ctx, resId)
        val fh = bmp.height
        val frames = (bmp.width / fh).coerceAtLeast(1)
        val fw = bmp.width / frames

        val trims = ArrayList<Rect>(frames)
        for (i in 0 until frames) {
            val sx = i * fw
            // compute bounds inside this frame
            val frameBmp = Bitmap.createBitmap(bmp, sx, 0, fw, fh)
            val local = BitmapUtils.computeOpaqueBounds(frameBmp) ?: Rect(0, 0, fw, fh)
            // convert to strip coordinates
            trims += Rect(sx + local.left, local.top, sx + local.right, local.bottom)
            frameBmp.recycle()
        }
        return Strip(bmp, frames, fw, fh, speed, loop, trims)
    }

    // Load strips (tweak speeds to taste)
    private val strips: Map<Anim, Strip> = mapOf(
        Anim.IDLE to loadStrip(R.drawable.soldier_idle, 2, loop = true),
        Anim.WALK to loadStrip(R.drawable.soldier_walk, 2, loop = true),
        Anim.ATTACK to loadStrip(R.drawable.soldier_attack03, 1, loop = false),
        Anim.HURT to loadStrip(R.drawable.soldier_hurt, 2, loop = false)
    )

    init {
        val ref = strips[Anim.IDLE]!!
        val trim0 = ref.trims[0]
        val aspect = trim0.width().toFloat() / trim0.height().toFloat() // w/h from TRIMMED art

        h = heightInTiles * tile
        w = h * aspect * 0.5f

        // If you kept a renderScale, set it to 1f so visual == physics.
        // Otherwise, increase heightInTiles to make the character bigger.
        // renderScale = 1f
    }

    private var anim = Anim.IDLE

    private val srcRect = Rect()
    private val dstRect = RectF()
    private val paint = Paint().apply {
        isFilterBitmap = false // crisp pixel art when scaling
        isDither = false
    }

    // Animation state
    private var frame = 0
    private var tick = 0

    // Visual scale (doesn't change collisions)
    private val renderScale = 1.2f     // height multiplier vs physics h
    private val desiredWorldH get() = h * renderScale

    // Facing: 1 right, -1 left
    private var facing = 1

    // one-shot timers
    private var hurtTimer = 0
    private val hurtDuration = 15

    private var attackTimer = 0
    private val attackCooldown = 30

    // quiver & firing
    val maxAmmo = 5
    private val quiver = Quiver(maxAmmo = maxAmmo, cooldownTicks = 60)
    val ammo get() = quiver.ammo()
    private var queuedOut: MutableList<Projectile>? = null
    private var queuedDir = 1
    private var firedThisAttack = false
    private val ATTACK_FIRE_FRAME = 7 // spawn arrow when attack reaches this frame

    private fun startOneShot(animType: Anim): Int {
        val s = strips[animType]!!
        return s.frames * s.speed
    }

    val isAttacking: Boolean get() = attackTimer > 0 // val is read only, var can be changed
    fun tryShoot(out: MutableList<Projectile>) {
        if (!quiver.canFire()) return
        val dir = if (vx > 0.05f) 1 else if (vx < -0.05f) -1 else facing
        facing = dir

        attackTimer = startOneShot(Anim.ATTACK)
        anim = Anim.ATTACK
        frame = 0
        tick = 0
        firedThisAttack = false
        queuedOut = out
        queuedDir = dir
    }

    fun draw(c: Canvas, gameState: GameState) {
        // Update facing
        if (vx > 0.05f) facing = 1 else if (vx < -0.05f) facing = -1

        // Priority: HURT > ATTACK > WALK > IDLE
        val moving = abs(vx) > 0.12f || !canJump
        val nextAnim = when {
            hurtTimer > 0 -> Anim.HURT
            attackTimer > 0 -> Anim.ATTACK
            moving -> Anim.WALK
            else -> Anim.IDLE
        }

        // Reset frame when animation changes
        if (nextAnim != anim) {
            anim = nextAnim; frame = 0; tick = 0
        }

        val strip = strips[anim]!!

        // Advance frames
        if (++tick % strip.speed == 0) {
            frame = if (strip.loop) (frame + 1) % strip.frames
            else (frame + 1).coerceAtMost(strip.frames - 1)
        }

        // --- use TRIMMED source for this frame ---
        val trim = strip.trims[frame]
        srcRect.set(trim.left, trim.top, trim.right, trim.bottom)

        // Desired on-screen/world size: make the *character* exactly desiredWorldH tall.
        val srcWpx = (trim.right - trim.left).toFloat()
        val srcHpx = (trim.bottom - trim.top).toFloat()
        val scale = desiredWorldH / srcHpx
        val drawW = srcWpx * scale
        val drawH = srcHpx * scale

        // Keep feet on ground: bottom of sprite = bottom of physics box (y + h)
        val left = x - abs(drawW - w) / 2f
        val top = y + h - drawH
        // IDK why I have to move the sprite to the right by w/2 to center it, but oh well
        dstRect.set(left, top, left + drawW, top + drawH)

        // Optional: blink while hurt
        val blink = (hurtTimer > 0) && ((hurtTimer / 2) % 2 == 0)

        // countdowns
        if (hurtTimer > 0) hurtTimer--
        if (attackTimer > 0) attackTimer--

        // --- Fire when reaching the attack keyframe ---
        if (anim == Anim.ATTACK && !firedThisAttack && frame >= ATTACK_FIRE_FRAME) {
            val out = queuedOut
            if (out != null && quiver.tryConsume(gameState)) {
                val spawnX = x + w / 2f
                val spawnY = y + h * 0.47f
                val speed = 14.0f
                val showHitbox = debugShowHitbox
                out.add(Arrow(spawnX, spawnY, speed * queuedDir, ctx, showHitbox))
            }
            firedThisAttack = true
            queuedOut = null
        }

        // tick quiver reloads
        quiver.tick(gameState)

        c.withSave {
            if (facing == -1) c.scale(-1f, 1f, dstRect.centerX(), dstRect.centerY())
            if (!blink) c.drawBitmap(strip.bmp, srcRect, dstRect, paint)
        }

        // Debug hitbox overlay
        drawDebugHitbox(c)
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
    }
}