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

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null
    @Volatile
    private var running = false

    // Core
    private val camera = Camera()
    private val input = InputController(this)
    private val state = GameState()

    // World & Entities
    private val map = TileMap(context)
    private val player = Player(map.playerStartX.toFloat(), map.playerStartY.toFloat(), context)
    private val enemies = mutableListOf<Enemy>().apply {
        addAll(map.spawnPoints.map {
            Enemy(
                it.first.toFloat(),
                it.second.toFloat(),
                context
            )
        })
    }
    private var boss = Boss(map.bossStartX.toFloat(), map.bossStartY.toFloat(), context)
    private val bullets = mutableListOf<Bullet>()
    private val enemyBullets = mutableListOf<Bullet>()
    private val clouds = MutableList(12) { i -> Cloud(i * 300f + 50f, 60f + (i % 3) * 30f) }

    private val sound = SoundManager(context)

    private var paused = false
    private var gameOver = false
    private var victory = false

    private val overlay = com.example.game_android.game.core.OverlayButtons()

    // Rendering helpers
    private val hud = HudRenderer(input)

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    // --- Surface callbacks ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true; thread = Thread(this).apply { start() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false; try {
            thread?.join()
        } catch (_: InterruptedException) {
        }
        sound.release()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
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

        // Background
        clouds.forEach { it.update(camera, width) }

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
        if (input.fire) player.tryShoot(bullets)

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
                enemyBullets,
                player.x,
                player.y
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
            c.drawColor(Color.rgb(35, 38, 52))
            map.drawBackground(c, camera, clouds)
            c.save(); c.translate(-camera.x, -camera.y)
            map.drawTiles(c)
            enemies.forEach { it.draw(c) }
            boss.draw(c)
            bullets.forEach { it.draw(c) }
            enemyBullets.forEach { it.draw(c) }
            player.draw(c)
            c.restore()
            hud.drawHud(c, player, boss, state)
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
                it.first.toFloat(),
                it.second.toFloat(),
                context
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
                    x = x, y = y, state = state,
                    onPauseToggle = { state.paused = !state.paused },
                    onExit = { (context as? android.app.Activity)?.finish() },
                    onContinue = { state.paused = false },
                    onRetry = { resetGame() },
                    onBackToMenu = { (context as? android.app.Activity)?.finish() }
                )
                if (handledOverlay) { performClick(); return true }

                // 2) Nếu không đụng overlay → chuyển sự kiện cho InputController (HUD: ← → A B II)
                input.onTouchEvent(event, state)
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
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