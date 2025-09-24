package com.example.game_android.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.game_android.game.core.Camera
import com.example.game_android.game.core.Constants
import com.example.game_android.game.core.InputController
import com.example.game_android.game.core.SoundManager
import com.example.game_android.game.entities.*
import com.example.game_android.game.ui.HudRenderer
import com.example.game_android.game.world.GameState
import com.example.game_android.game.world.TileMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.withTranslation

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
    private val enemyBullets = mutableListOf<Bullet>()

    private val sound = SoundManager(context)

    // Rendering helpers
    private val hud = HudRenderer(input, context)

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        player.debugShowHitbox = true
    }

    // --- Surface callbacks ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this).apply { start() }
        sound.startBgmLoop()
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
        var last = System.nanoTime();
        val step = 1_000_000_000.0 / 60.0;
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
            input.left && !input.right -> player.vx =
                max(player.vx - accel, -Constants.PLAYER_MAX_SPD)

            input.right && !input.left -> player.vx =
                min(player.vx + accel, Constants.PLAYER_MAX_SPD)

            else -> player.vx *= 0.8f
        }
        if (input.jumpPressed(player)) {
            player.vy = -Constants.JUMP_VELOCITY; player.canJump = false
        }

        // Physics
        player.vy += Constants.GRAVITY
        map.moveAndCollide(player)

        // Shooting
        if (input.fire) player.tryShoot(arrows)

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

        // Boss
        if (boss.alive) {
            boss.vy += Constants.GRAVITY
            boss.vx = when {
                player.x < boss.x - 60 -> -1.4f; player.x > boss.x + 60 -> 1.4f; else -> 0f
            }
            map.moveAndCollide(boss)
            boss.tryShoot(enemyBullets, player.x, player.y)
        }

        // update
        arrows.forEach { it.update(map.pixelWidth) }
        arrows.removeAll {
            it.dead || map.isSolidAtPxRect(it.bounds())
        }

// collisions (reuse your bullet collision blocks, but with arrows list)
        arrows.forEach { a ->
            enemies.forEach { e ->
                if (e.alive && a.overlaps(e)) {
                    e.hit(); a.dead = true; sound.playHitEnemy()
                }
            }
            if (boss.alive && a.overlaps(boss)) {
                boss.hit(); a.dead = true; sound.playHitEnemy()
                if (!boss.alive) state.victory = true
            }
        }

        // Bullets & collisions
        bullets.forEach { it.update(map.pixelWidth) }
        enemyBullets.forEach { it.update(map.pixelWidth) }
        bullets.removeAll { it.dead || map.isSolidAtPx(it.x.toInt(), it.y.toInt()) }
        enemyBullets.removeAll { it.dead || map.isSolidAtPx(it.x.toInt(), it.y.toInt()) }
        bullets.forEach { b ->
            enemies.forEach { e ->
                if (e.alive && b.overlaps(e)) {
                    e.hit()
                    b.dead = true
                    sound.playHitEnemy()
                }
            }
            if (boss.alive && b.overlaps(boss)) {
                boss.hit()
                b.dead = true
                sound.playHitEnemy()
                if (!boss.alive) state.victory = true
            }
        }
        enemyBullets.forEach { b ->
            if (b.overlaps(player)) {
                player.hit()
                b.dead = true
                sound.playPlayerDie()
                if (player.hp <= 0) state.gameOver = true
            }
        }

        // Camera follow
        camera.follow(player.x, player.y, width, height, map.pixelWidth, map.pixelHeight)
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
                ;
                map.drawTiles(this)
                enemies.forEach { it.draw(this) }
                boss.draw(this)
                bullets.forEach { it.draw(this) }
                enemyBullets.forEach { it.draw(this) }
                player.draw(this)
                arrows.forEach { it.draw(this) }
            }

// draw

            hud.drawHud(
                c, player, boss, state, isMuted = sound.isMuted, isBgMuted = sound.isBgMuted
            )
            hud.drawOverlays(c, state) { action ->
                when (action) {
                    HudRenderer.Action.PauseToggle -> state.paused = !state.paused
                    HudRenderer.Action.Exit -> (context as? android.app.Activity)?.finish()
                    HudRenderer.Action.Continue -> state.paused = false
                    HudRenderer.Action.Retry -> resetGame()
                    HudRenderer.Action.BackToMenu -> (context as? android.app.Activity)?.finish()
                }
            }
        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

    private fun resetGame() {
        player.reset(map.playerStartX.toFloat(), map.playerStartY.toFloat())
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(event.actionIndex)
                val y = event.getY(event.actionIndex)

                // 1) Ưu tiên: xử lý click vào các nút Overlay (Pause/GameOver/Victory)
                val handledOverlay = hud.handleTouch(
                    x = x,
                    y = y,
                    state = state,
                    onPauseToggle = { state.paused = !state.paused },
                    onMuteToggle = { sound.toggleMute() },
                    onBgMuteToggle = { sound },
                    onExit = { (context as? android.app.Activity)?.finish() },
                    onContinue = { state.paused = false },
                    onRetry = { resetGame() },
                    onBackToMenu = { (context as? android.app.Activity)?.finish() })
                if (handledOverlay) {
                    performClick(); return true
                }

                // 2) Nếu không đụng overlay → chuyển sự kiện cho InputController (HUD: ← → A B II)
                input.onTouchEvent(event, state)
            }

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // HUD controls (giữ ← rồi bấm A, v.v.)
                input.onTouchEvent(event, state)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick(); return true
    }
}