package com.example.game_android.game

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.graphics.withTranslation
import com.example.game_android.game.core.Camera
import com.example.game_android.game.core.Constants
import com.example.game_android.game.core.InputController
import com.example.game_android.game.core.SoundManager
import com.example.game_android.game.entities.Boss
import com.example.game_android.game.entities.Bullet
import com.example.game_android.game.entities.Enemy
import com.example.game_android.game.entities.Fireball
import com.example.game_android.game.entities.Player
import com.example.game_android.game.entities.Projectile
import com.example.game_android.game.entities.Witch
import com.example.game_android.game.ui.HudRenderer
import com.example.game_android.game.world.GameState
import com.example.game_android.game.world.TileMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null

    @Volatile
    private var running = false

    // Core
    private val camera = Camera()
    private val input = InputController(this, context)
    private val state = GameState()

    // World & Entities
    private val map = TileMap(context)
    private val player = Player(map.playerStartX.toFloat(), map.playerStartY.toFloat(), context)
    private val witches = mutableListOf<Witch>()
    private val enemies = mutableListOf<Enemy>().apply {
        addAll(map.spawnPoints.map {
            Enemy(
                it.first.toFloat(), it.second.toFloat(), context
            )
        })
    }
    private var boss = Boss(map.bossStartX.toFloat(), map.bossStartY.toFloat(), context)
    private val bullets = mutableListOf<Bullet>()
    private val arrows = mutableListOf<Projectile>()
    private val enemyBullets = mutableListOf<Projectile>()

    private val sound = SoundManager(context)

    // Rendering helpers
    private val hud = HudRenderer(input, context)

    private var footstepStreamId: Int = 0

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        initWitches()
        //player.debugShowHitbox = true
        //witches.forEach { witch -> witch.showHitbox = true }
    }

    // --- Surface callbacks ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this).apply { start() }
        sound.setBgmMasterAttenuation(0.1f)
        sound.setSfxMasterAttenuation(0.5f)
        sound.startBgmIfNeeded()
    }


    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false; try {
            thread?.join()
        } catch (_: InterruptedException) {
        }
        sound.release()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        map.initBackground(width, height)
        input.layout(width, height)
    }

    // --- Game loop (fixed-step 60 FPS) ---
    override fun run() {
        var last = System.nanoTime()
        val step = 1_000_000_000.0 / 60.0
        var acc = 0.0
        while (running) {
            val now = System.nanoTime(); acc += (now - last); last = now
            var updated = false
            while (acc >= step) {
                update(); acc -= step; updated = true
            }
            if (updated) drawFrame()
        }
    }

    // --- Update world ---
    private fun update() {
        if (state.anyOverlay()) return

        // Input → movement
        val accel = 0.8f
        when {
            input.left && !input.right && !player.isAttacking -> player.vx =
                max(player.vx - accel, -Constants.PLAYER_MAX_SPD)

            input.right && !input.left && !player.isAttacking -> player.vx =
                min(player.vx + accel, Constants.PLAYER_MAX_SPD)

            else -> player.vx *= 0.8f
        }
        if (input.jumpPressed(player) && !player.isAttacking) {
            player.vy = -Constants.JUMP_VELOCITY; player.canJump = false
        }

        // Consider walking only when on ground, moving, and not attacking
        val speed = abs(player.vx)
        val isWalking =
            player.canJump && speed > 0.2f && !player.isAttacking  // threshold avoids flicker

        if (isWalking) {
            // Start loop if not running
            if (footstepStreamId == 0) {
                // Volume scales mildly with speed; tune 0.35f..0.8f as you like
                val baseVol = 0.35f + 0.45f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
                // Slightly faster steps as you move faster
                val rate = 0.9f + 0.3f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
                footstepStreamId =
                    sound.playLoop(SoundManager.Sfx.PlayerWalk, volume = baseVol, rate = rate)
            } else {
                // Update loop volume/rate continuously (smooth with same mapping)
                val baseVol = 0.35f + 0.45f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
                val rate = 0.9f + 0.3f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
                sound.setLoopVolume(footstepStreamId, baseVol)
                sound.setLoopRate(footstepStreamId, rate)
            }
        } else {
            // Not walking → stop if running
            if (footstepStreamId != 0) {
                sound.stopLoop(footstepStreamId)
                footstepStreamId = 0
            }
        }

        // Physics
        player.vy += Constants.GRAVITY
        map.moveAndCollide(player)

        // Shooting
        if (input.fire && !player.isAttacking && player.ammo > 0) {
            player.tryShoot(arrows)
            sound.play(SoundManager.Sfx.PlayerShoot)
        }

        // Enemies
        enemies.forEach { e ->
            if (!e.alive) return@forEach
            e.vy += Constants.GRAVITY
            e.vx = if (abs(e.x - player.x) < 280f) {
                if (player.x < e.x) -1.2f else 1.2f
            } else {
                if (e.patrolRight) 1.0f else -1.0f
            }
            map.moveAndCollide(e)
            if (abs(e.x - player.x) < 340 && abs(e.y - player.y) < 120) e.tryShoot(
                enemyBullets, player.x, player.y
            )
        }
        // Witches
        witches.forEach { w ->
            if (!w.alive) return@forEach
            w.vy += Constants.GRAVITY
            // Update AI (no jump yet) — onGround = w.canJump after collision
            // But we need to collide first to know canJump → do a tentative AI step, then collide, then finalize anim
            // Simple approach: do AI using last frame canJump, then collide, then refresh anim state.
            val onGroundPrev = w.canJump
            w.updateAiAndAnim(player.x, player.y, onGroundPrev)
            map.moveAndCollide(w)
            w.updateAiAndAnim(player.x, player.y, w.canJump) // refresh anim if ground state changed

            // LoS is optional; if you have a helper, gate by LoS. Here we just use range.
            val inRange =
                abs((player.x + player.w * 0.5f) - (w.x + w.w * 0.5f)) < 520f && abs(
                    (player.y + player.h * 0.5f) - (w.y + w.h * 0.5f)
                ) < 280f
            if (inRange) {
                w.tryShootFireball(
                    enemyBullets /* <- shared bucket */,
                    player.x + player.w * 0.5f,
                    player.y + player.h * 0.4f
                )
            }
        }

        // Boss
        if (boss.alive) {
            boss.vy += Constants.GRAVITY
            boss.vx = when {
                player.x < boss.x - 60 -> -1.4f; player.x > boss.x + 60 -> 1.4f; else -> 0f
            }
            map.moveAndCollide(boss)
            boss.tryShoot(enemyBullets, player.x, player.y)
        }

        arrows.forEach { a ->
            enemies.forEach { e ->
                if (e.alive && a.overlaps(e)) {
                    Log.d("GameView", "Arrow hit enemy at ${e.x},${e.y}")
                    e.hit(); a.dead = true; sound.playArrowHitEnemy()
                }
            }
            witches.forEach { w ->
                if (w.alive && a.overlaps(w)) {
                    sound.playArrowHitEnemy()
                    w.hit(); a.dead = true;
                }
            }
            if (boss.alive && a.overlaps(boss)) {
                Log.d("GameView", "Arrow hit BOSS at ${boss.x},${boss.y}")
                boss.hit(); a.dead = true; sound.play(SoundManager.Sfx.ArrowHitEnemy)
                if (!boss.alive) state.victory = true
            }
            a.update(map.pixelWidth)
        }

        // Bullets & collisions
        bullets.forEach { it.update(map.pixelWidth) }
        enemyBullets.forEach { it.update(map.pixelWidth) }
        bullets.removeAll { it.dead || map.isSolidAtPx(it.x.toInt(), it.y.toInt()) }

        bullets.forEach { b ->
            enemies.forEach { e ->
                if (e.alive && b.overlaps(e)) {
                    e.hit()
                    b.dead = true
                    sound.play(SoundManager.Sfx.ArrowHitEnemy)
                }
            }
            if (boss.alive && b.overlaps(boss)) {
                boss.hit()
                b.dead = true
                sound.play(SoundManager.Sfx.ArrowHitEnemy)
                if (!boss.alive) state.victory = true
            }
        }
        enemyBullets.forEach { b ->
            if (b.overlaps(player)) {
                if (b is Fireball) {
                    // Deal damage once, then explode (don’t kill immediately)
                    if (!b.alreadyDamagedPlayer()) {
                        player.hit()
                        sound.play(SoundManager.Sfx.PlayerHurt)
                        sound.playFireballExplode()
                        if (player.hp <= 0) state.gameOver = true
                    }
                    b.startExplode(hitPlayer = true)
                } else {
                    // legacy bullets
                    player.hit()
                    b.dead = true
                    sound.play(SoundManager.Sfx.PlayerHurt)
                    if (player.hp <= 0) state.gameOver = true
                }
            }
        }

        // Camera follow
        camera.follow(player.x, player.y, width, height, map.pixelWidth, map.pixelHeight)
        // ---- Cull arrows by current on-screen viewport (with a small margin) ----
        run {
            val worldYOffset =
                (height - map.pixelHeight).coerceAtLeast(0f) // same offset you use when drawing
            val margin = 64f

            // Camera viewport in world coords (adjust top with worldYOffset to match draw-space)
            val left = camera.x - margin
            val right = camera.x + width + margin
            val top = camera.y - worldYOffset - margin
            val bottom = top + height + margin

            arrows.removeAll { a ->
                if (map.isSolidAtPxRect(a.bounds())) {
                    Log.d("GameView", "Culling arrow at ${a.x},${a.y}")
                    sound.playArrowHitWall()
                    true
                } else if (a.dead || (a.x + a.w) < left || a.x > right || (a.y + a.h) < top || a.y > bottom) {
                    true
                } else {
                    false
                }
            }
        }

        // --- Enemy projectiles: update, sweep-collide with tiles, keep during explosion ---
        run {
            enemyBullets.forEach { b ->
                val oldX = b.x
                val oldY = b.y

                // Step the projectile (Fireball also advances animation here)
                b.update(map.pixelWidth)

                // --- Tile collision: Fireball explodes, others get removed later ---
                if (b is Fireball && !b.isExploding()) {
                    val dx = b.x - oldX
                    val dy = b.y - oldY

                    // Sweep to avoid tunneling
                    val stepLen = (Constants.TILE.toFloat() * 0.25f).coerceAtLeast(
                        2f
                    )
                    val steps = max(
                        1, (max(
                            abs(dx), abs(dy)
                        ) / stepLen).toInt()
                    )

                    var hit = false
                    var hitX = b.x
                    var hitY = b.y

                    for (i in 1..steps) {
                        val t = i / steps.toFloat()
                        val tx = oldX + dx * t
                        val ty = oldY + dy * t
                        val rect = android.graphics.RectF(tx, ty, tx + b.w, ty + b.h)
                        if (map.isSolidAtPxRect(rect)) {
                            hit = true; hitX = tx; hitY = ty; break
                        }
                    }

                    if (hit) {
                        b.x = hitX; b.y = hitY
                        b.startExplode(hitPlayer = false)
                        Log.d("GameView", "Fireball hit wall at ${b.x},${b.y}")
                        sound.playFireballExplode()
                    }
                }
            }

            // Remove only when finished:
            enemyBullets.removeAll { b ->
                when (b) {
                    is Fireball -> b.dead  // keep while exploding
                    else -> (b.dead || map.isSolidAtPxRect(b.bounds())) // legacy bullets: old behavior
                }
            }
        }

        run {
            witches.removeAll { it.isDeadAndGone() }
        }
    }

    // --- Render frame ---
    private fun drawFrame() {
        val c = holder.lockCanvas() ?: return
        try {
            // (Optional) clear if you like; parallax already paints the screen.
            // c.drawColor(Color.rgb(35, 38, 52))
            map.updateBackground(camera) // NEW: compute layer offsets from camera.x
            map.drawBackground(c)        // NEW: draw 7-layer looping parallax

            // If world is shorter than the view, push it down so the floor sits at the bottom.
            val worldYOffset = (height - map.pixelHeight).coerceAtLeast(0f)

            c.withTranslation(-camera.x, -camera.y + worldYOffset) {
                map.drawTiles(this)
                map.drawDebugTiles(this, camera.x, camera.y, width, height)
                enemies.forEach { it.draw(this) }
                witches.forEach { it.draw(this) }
                boss.draw(this)
                bullets.forEach { it.draw(this) }
                enemyBullets.forEach { it.draw(this) }
                player.draw(this, gameState = state)
                arrows.forEach { it.draw(this) }
            }

            // draw
            hud.drawHud(
                c, player, boss, player.maxAmmo, player.ammo
            )
            hud.drawOverlays(c, state, sound.bgmVolumeUi, sound.sfxVolumeUi)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

    private fun resetGame() {
        player.reset(map.playerStartX.toFloat(), map.playerStartY.toFloat())
        initWitches()
        enemies.clear(); enemies.addAll(map.spawnPoints.map {
            Enemy(
                it.first.toFloat(), it.second.toFloat(), context
            )
        })
        boss = Boss(map.bossStartX.toFloat(), map.bossStartY.toFloat(), context)
        bullets.clear(); enemyBullets.clear()
        state.reset()
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        // 1) Ưu tiên: xử lý click vào các nút Overlay (Pause/GameOver/Victory)
        val handledOverlay = hud.handleTouch(
            event.actionMasked,
            x = x,
            y = y,
            state = state,
            onPauseToggle = {
                state.paused = !state.paused
            },
            onExit = { (context as? android.app.Activity)?.finish() },
            onContinue = { state.paused = false },
            onRetry = { resetGame() },
            onBackToMenu = { (context as? android.app.Activity)?.finish() },
            onSetBgmVolume = { v -> sound.setBgmVolume(v) },
            onSetSfxVolume = { v -> sound.setSfxVolume(v) })
        if (handledOverlay) {
            performClick(); return true
        }

        // 2) Nếu không đụng overlay → chuyển sự kiện cho InputController (HUD: ← → A B II)
        input.onTouchEvent(event, state)
        return true
    }

    private fun initWitches() {
        witches.clear(); witches.addAll(map.witchSpawns.map { (sx, sy) ->
            Witch(
                sx.toFloat(),
                sy.toFloat(),
                context
            )
        })
        witches.forEach { witch ->
            witch.onDeath = { sound.playWitchDie() }
            witch.onHurt = { sound.play(SoundManager.Sfx.WitchHurt) }
            witch.onThrowFireball = { sound.play(SoundManager.Sfx.FireballShoot) }
        }
    }

    override fun performClick(): Boolean {
        super.performClick(); return true
    }
}