package com.example.game_android.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null
    @Volatile private var running = false

    init {
        holder.addCallback(this)
        // Ensure we accept multi-touch and keep screen awake
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    // (moved the rest of fields below)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val tileSize = 32
    private val gravity = 0.5f

    // World 500x20 -> 16000x640 px
    private val worldCols = 500
    private val worldRows = 20

    // holder & focus already set above
    private val map: TileMap = TileMap(tileSize, worldCols, worldRows)
    private val camera = Camera()

    // Entities
    private val player: Player
    private val enemies = mutableListOf<Enemy>()
    private var boss: Boss
    private val bullets = mutableListOf<Bullet>()
    private val enemyBullets = mutableListOf<Bullet>()
    private val clouds = mutableListOf<Cloud>()

    // UI state
    private var paused = false
    private var gameOver = false
    private var victory = false

    // Controls (on-screen)
    private val btnLeft = RectF()
    private val btnRight = RectF()
    private val btnJump = RectF()
    private val btnFire = RectF()
    private val btnPause = RectF()

    // Touch state
    private var leftDown = false
    private var rightDown = false
    private var jumpDown = false
    private var fireDown = false

    init {
        val startPos = map.playerStart
        player = Player(startPos.first.toFloat(), startPos.second.toFloat())
        enemies.addAll(map.spawnPoints.map { (x,y) -> Enemy(x.toFloat(), y.toFloat()) })
        boss = Boss(map.bossStart.first.toFloat(), map.bossStart.second.toFloat())
        repeat(12) { i -> clouds.add(Cloud(i * 300f + 50f, 60f + (i%3)*30f)) }
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this).apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // layout control zones
        val pad = 16f
        val bw = width * 0.12f
        val bh = height * 0.18f
        btnLeft.set(pad, height - bh - pad, pad + bw, height - pad)
        btnRight.set(btnLeft.right + pad, btnLeft.top, btnLeft.right + pad + bw, btnLeft.bottom)
        btnJump.set(width - bw - pad, height - bh - pad, width - pad, height - pad)
        btnFire.set(btnJump.left - pad - bw, btnJump.top, btnJump.left - pad, btnJump.bottom)
        btnPause.set(width - pad - bw*0.7f, pad, width - pad, pad + bh*0.5f)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try { thread?.join() } catch (_: InterruptedException) {}
    }

    override fun run() {
        var last = System.nanoTime()
        val targetDelta = 1_000_000_000.0 / 60.0 // 60 FPS target
        var accumulator = 0.0
        while (running) {
            val now = System.nanoTime()
            accumulator += (now - last)
            last = now
            var updated = false
            while (accumulator >= targetDelta) {
                update()
                accumulator -= targetDelta
                updated = true
            }
            if (updated) drawFrame()
        }
    }

    private fun drawFrame() {
        val c = holder.lockCanvas() ?: return
        try {
            c.drawColor(Color.rgb(35, 38, 52))

            // Parallax sky + clouds
            paint.color = Color.rgb(19, 22, 34)
            c.drawRect(0f,0f,width.toFloat(),height*0.6f, paint)
            clouds.forEach { it.draw(c, camera) }

            // World
            c.withTranslation(-camera.x, -camera.y) {
                map.draw(this, paint)
                enemies.forEach { it.draw(this) }
                boss.draw(this)
                bullets.forEach { it.draw(this) }
                enemyBullets.forEach { it.draw(this) }
                player.draw(this)
            }

            // UI: health, boss hp, pause button
            drawHUD(c)

            if (paused) drawPauseOverlay(c)
            if (gameOver) drawGameOverOverlay(c)
            if (victory) drawVictoryOverlay(c)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

    private fun drawHUD(c: Canvas) {
        // Hearts
        val hp = player.hp
        val r = RectF(16f, 16f, 48f, 48f)
        repeat(3) { i ->
            paint.color = if (i < hp) Color.RED else Color.DKGRAY
            c.drawRoundRect(r.left + i*40, r.top, r.right + i*40, r.bottom, 8f, 8f, paint)
        }
        // Boss HP bar if nearby/end
        if (!victory && boss.alive) {
            val barW = width * 0.4f
            val bar = RectF(width/2f - barW/2, 16f, width/2f + barW/2, 28f)
            paint.color = Color.GRAY
            c.drawRect(bar, paint)
            paint.color = Color.MAGENTA
            val w = barW * (boss.hp/30f)
            c.drawRect(RectF(bar.left, bar.top, bar.left + w, bar.bottom), paint)
        }
        // Control buttons
        drawButton(c, btnLeft, "←")
        drawButton(c, btnRight, "→")
        drawButton(c, btnJump, "A")
        drawButton(c, btnFire, "B")
        drawButton(c, btnPause, "II")
    }

    private fun drawButton(c: Canvas, r: RectF, label: String) {
        paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
            Color.argb(160, 60, 60, 70), Color.argb(160, 30, 30, 40), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 20f, 20f, paint)
        paint.shader = null
        text.textAlign = Paint.Align.CENTER
        text.textSize = min(r.width(), r.height()) * 0.5f
        c.drawText(label, r.centerX(), r.centerY() + text.textSize/3f, text)
    }

    private fun drawPauseOverlay(c: Canvas) {
        paint.color = Color.argb(180, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 64f
        c.drawText("PAUSED", width/2f, height*0.35f, text)
        val bw = width*0.3f; val bh = height*0.12f
        val cont = RectF(width/2f-bw/2, height*0.45f, width/2f+bw/2, height*0.45f+bh)
        val exit = RectF(width/2f-bw/2, cont.bottom+24f, width/2f+bw/2, cont.bottom+24f+bh)
        drawButton(c, cont, "Continue")
        drawButton(c, exit, "Exit")
        pauseContinueRect = cont
        pauseExitRect = exit
    }

    private fun drawGameOverOverlay(c: Canvas) {
        paint.color = Color.argb(200, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 64f
        c.drawText("GAME OVER", width/2f, height*0.35f, text)
        val bw = width*0.3f; val bh = height*0.12f
        val retry = RectF(width/2f-bw/2, height*0.45f, width/2f+bw/2, height*0.45f+bh)
        val exit = RectF(width/2f-bw/2, retry.bottom+24f, width/2f+bw/2, retry.bottom+24f+bh)
        drawButton(c, retry, "Retry")
        drawButton(c, exit, "Exit")
        gameOverRetryRect = retry
        gameOverExitRect = exit
    }

    private fun drawVictoryOverlay(c: Canvas) {
        paint.color = Color.argb(200, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 64f
        c.drawText("VICTORY!", width/2f, height*0.35f, text)
        val bw = width*0.4f; val bh = height*0.12f
        val exit = RectF(width/2f-bw/2, height*0.45f, width/2f+bw/2, height*0.45f+bh)
        drawButton(c, exit, "Back to Menu")
        victoryExitRect = exit
    }

    // Overlay button rects
    private var pauseContinueRect = RectF()
    private var pauseExitRect = RectF()
    private var gameOverRetryRect = RectF()
    private var gameOverExitRect = RectF()
    private var victoryExitRect = RectF()

    private fun update() {
        if (paused || gameOver || victory) return

        // Cloud motion
        clouds.forEach { it.update() }

        // Player control input
        val accel = 0.8f
        when {
            leftDown && !rightDown -> player.vx = max(player.vx - accel, -3.5f)
            rightDown && !leftDown -> player.vx = min(player.vx + accel, 3.5f)
            else -> player.vx *= 0.8f
        }
        if (jumpDown && player.canJump && !player.wasJumpDown) {
            player.vy = -9.5f
            player.canJump = false
        }
        player.wasJumpDown = jumpDown

        // Apply physics + collisions
        player.vy += gravity
        moveAndCollide(player)

        // Shooting
        if (fireDown) player.tryShoot(bullets)

        // Enemies
        enemies.forEach { e ->
            if (!e.alive) return@forEach
            e.vy += gravity
            // simple patrol
            e.vx = if (abs(e.x - player.x) < 280f) {
                // face player lightly
                if (player.x < e.x) -1.2f else 1.2f
            } else {
                if (e.patrolRight) 1.0f else -1.0f
            }
            moveAndCollide(e)
            // shoot if near line of sight
            if (abs(e.x - player.x) < 340 && abs(e.y - player.y) < 120) e.tryShoot(enemyBullets, player.x, player.y)
        }

        // Boss
        if (boss.alive) {
            boss.vy += gravity
            // boss stays near its zone, small horizontal moves towards player
            boss.vx = when {
                player.x < boss.x - 60 -> -1.4f
                player.x > boss.x + 60 -> 1.4f
                else -> 0f
            }
            moveAndCollide(boss)
            boss.tryShoot(enemyBullets, player.x, player.y)
        }

        // Bullets
        bullets.forEach { it.update() }
        enemyBullets.forEach { it.update() }
        bullets.removeAll { it.dead || map.isSolidAtPx(it.x.toInt(), it.y.toInt()) }
        enemyBullets.removeAll { it.dead || map.isSolidAtPx(it.x.toInt(), it.y.toInt()) }

        // Bullet collisions
        bullets.forEach { b ->
            enemies.forEach { e -> if (e.alive && rectOverlap(b.bounds(), e.bounds())) { e.hit(); b.dead = true } }
            if (boss.alive && rectOverlap(b.bounds(), boss.bounds())) { boss.hit(); b.dead = true; if (!boss.alive) victory = true }
        }
        enemyBullets.forEach { b ->
            if (rectOverlap(b.bounds(), player.bounds())) { player.hit(); b.dead = true; if (player.hp <= 0) gameOver = true }
        }

        // Camera follow
        camera.x = (player.x - width/2f).coerceIn(0f, map.pixelWidth - width.toFloat())
        camera.y = (player.y - height/2f).coerceIn(0f, map.pixelHeight - height.toFloat())
    }

    private fun rectOverlap(a: RectF, b: RectF): Boolean = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    // Movement + tile collisions with one-way '^'
    private fun moveAndCollide(e: PhysicsBody) {
        // Horizontal
        var nx = e.x + e.vx
        val hb = e.boundsAt(nx, e.y)
        if (map.collidesSolid(hb)) {
            nx = if (e.vx > 0) ((hb.right.toInt()/tileSize)*tileSize - e.w - 1) else ((hb.left.toInt()/tileSize + 1)*tileSize).toFloat()
            e.vx = 0f
        }
        e.x = nx

        // Vertical
        var ny = e.y + e.vy
        val vb = e.boundsAt(e.x, ny)
        val collideDown = e.vy >= 0
        val collideUp = e.vy < 0
        val hitSolid = map.collidesSolid(vb)
        val hitOneWayDown = collideDown && map.collidesOneWayFromAbove(vb)
        val hit = hitSolid || hitOneWayDown
        if (hit) {
            if (e.vy > 0) {
                ny = (vb.bottom - (vb.bottom % tileSize)).toInt().toFloat() - e.h - 0.1f
                e.canJump = true
            } else if (e.vy < 0 && hitSolid) {
                ny = (vb.top - (vb.top % tileSize) + tileSize).toInt().toFloat()
            }
            e.vy = 0f
        } else if (collideUp && map.collidesOneWayFromAbove(vb)) {
            // if jumping up through one-way, ignore
        }
        e.y = ny
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        fun handleDown(x: Float, y: Float) {
            when {
                btnPause.contains(x,y) -> paused = !paused
                paused && pauseContinueRect.contains(x,y) -> { paused = false }
                paused && pauseExitRect.contains(x,y) -> { paused = false; (context as? android.app.Activity)?.finish() }
                gameOver && gameOverRetryRect.contains(x,y) -> resetGame()
                gameOver && gameOverExitRect.contains(x,y) -> { (context as? android.app.Activity)?.finish() }
                victory && victoryExitRect.contains(x,y) -> { (context as? android.app.Activity)?.finish() }
                btnLeft.contains(x,y) -> { leftDown = true }
                btnRight.contains(x,y) -> { rightDown = true }
                btnJump.contains(x,y) -> { jumpDown = true }
                btnFire.contains(x,y) -> { fireDown = true }
            }
        }
        fun handleUp(x: Float, y: Float) {
            if (btnLeft.contains(x,y)) leftDown = false
            if (btnRight.contains(x,y)) rightDown = false
            if (btnJump.contains(x,y)) jumpDown = false
            if (btnFire.contains(x,y)) fireDown = false
        }
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                handleDown(event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                handleUp(event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                // recompute based on any finger inside
                leftDown = false; rightDown = false; jumpDown = false; fireDown = false
                for (i in 0 until event.pointerCount) {
                    val x = event.getX(i); val y = event.getY(i)
                    if (btnLeft.contains(x,y)) leftDown = true
                    if (btnRight.contains(x,y)) rightDown = true
                    if (btnJump.contains(x,y)) jumpDown = true
                    if (btnFire.contains(x,y)) fireDown = true
                }
            }
        }
        performClick()
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        // Thêm xử lý khi click nếu cần
        return true
    }

    private fun resetGame() {
        player.reset(map.playerStart.first.toFloat(), map.playerStart.second.toFloat())
        enemies.clear(); enemies.addAll(map.spawnPoints.map { (x,y) -> Enemy(x.toFloat(), y.toFloat()) })
        boss = Boss(map.bossStart.first.toFloat(), map.bossStart.second.toFloat())
        bullets.clear(); enemyBullets.clear()
        gameOver = false; victory = false; paused = false
    }

    // --------------------------
    // Types & Entities
    // --------------------------
    inner class Camera { var x = 0f; var y = 0f }

    interface PhysicsBody {
        var x: Float; var y: Float; var vx: Float; var vy: Float; val w: Float; val h: Float
        var canJump: Boolean
        fun bounds(): RectF = RectF(x, y, x + w, y + h)
        fun boundsAt(nx: Float, ny: Float): RectF = RectF(nx, ny, nx + w, ny + h)
    }

    inner class Player(px: Float, py: Float): PhysicsBody {
        override var x = px; override var y = py
        override var vx = 0f; override var vy = 0f
        override val w = 26f; override val h = 30f
        override var canJump = false
        var wasJumpDown = false
        var hp = 3
        private var fireCooldown = 0
        private val sprite = SpriteLoader.loadSet(context, "player_", 6)
        private var frame = 0; private var frameTick = 0
        fun draw(c: Canvas) {
            if (sprite != null) {
                val bmp = sprite[frame % sprite.size]
                c.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), null)
                if (++frameTick % 6 == 0) frame++
            } else {
                paint.color = Color.CYAN
                c.drawRect(x, y, x+w, y+h, paint)
            }
        }
        fun tryShoot(out: MutableList<Bullet>) {
            if (fireCooldown > 0) { fireCooldown--; return }
            val dir = if (vx >= 0) 1 else -1
            out.add(Bullet(x + w/2, y + h/2, 7f*dir))
            fireCooldown = 12
        }
        fun hit() { if (hp>0) hp-- }
        fun reset(px: Float, py: Float) { x = px; y = py; vx=0f; vy=0f; hp=3; canJump=false; wasJumpDown=false }
    }

    inner class Enemy(px: Float, py: Float): PhysicsBody {
        override var x = px; override var y = py
        override var vx = 0f; override var vy = 0f
        override val w = 24f; override val h = 28f
        override var canJump = false
        var patrolRight = true
        var alive = true
        private var shootCD = 0
        private val sprite = SpriteLoader.loadSet(context, "enemy_", 6)
        private var frame = 0; private var frameTick = 0
        fun draw(c: Canvas) {
            if (!alive) return
            if (sprite != null) {
                val bmp = sprite[frame % sprite.size]
                c.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), null)
                if (++frameTick % 8 == 0) frame++
            } else {
                paint.color = Color.YELLOW
                c.drawRect(x, y, x+w, y+h, paint)
            }
        }
        fun tryShoot(out: MutableList<Bullet>, tx: Float, ty: Float) {
            if (shootCD>0) { shootCD--; return }
            val dir = if (tx < x) -1 else 1
            out.add(Bullet(x + w/2, y + h/2, 5f*dir))
            shootCD = 60
        }
        fun hit() { alive = false }
    }

    inner class Boss(px: Float, py: Float): PhysicsBody {
        override var x = px; override var y = py
        override var vx = 0f; override var vy = 0f
        override val w = 48f; override val h = 54f
        override var canJump = false
        var hp = 30
        val alive get() = hp>0
        private var shootCD = 0
        private val sprite = SpriteLoader.loadSet(context, "boss_", 6)
        private var frame = 0; private var frameTick = 0
        fun draw(c: Canvas) {
            if (!alive) return
            if (sprite != null) {
                val bmp = sprite[frame % sprite.size]
                c.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), null)
                if (++frameTick % 6 == 0) frame++
            } else {
                paint.color = Color.MAGENTA
                c.drawRect(x, y, x+w, y+h, paint)
            }
        }
        fun tryShoot(out: MutableList<Bullet>, tx: Float, ty: Float) {
            if (!alive) return
            if (shootCD>0) { shootCD--; return }
            val dir = if (tx < x) -1 else 1
            out.add(Bullet(x + w/2, y + h*0.6f, 6f*dir))
            shootCD = 30
        }
        fun hit() { hp-- }
    }

    inner class Bullet(var x: Float, var y: Float, var vx: Float) {
        var dead = false
        val r = 4f
        fun update(){ x+=vx; if (x<0 || x>map.pixelWidth) dead = true }
        fun draw(c: Canvas){ paint.color = Color.WHITE; c.drawCircle(x, y, r, paint) }
        fun bounds(): RectF = RectF(x-r,y-r,x+r,y+r)
    }

    inner class Cloud(var x: Float, var y: Float) {
        private val w = 64f; private val h = 32f
        fun update(){ x += 0.2f; if (x - camera.x > width + 50) x -= 400f }
        fun draw(c: Canvas, cam: Camera) {
            paint.color = Color.argb(180, 220, 220, 240)
            c.drawOval(RectF(x - cam.x, y - cam.y, x - cam.x + w, y - cam.y + h), paint)
        }
    }

    object SpriteLoader {
        fun loadSet(ctx: Context, prefix: String, count: Int): List<Bitmap>? {
            val res = mutableListOf<Bitmap>()
            val r = ctx.resources
            for (i in 0 until count) {
                val name = prefix + i
                val id = r.getIdentifier(name, "drawable", ctx.packageName)
                if (id == 0) return null // if any missing -> use fallback
                res.add(BitmapFactory.decodeResource(r, id))
            }
            return res
        }
    }

    // --------------------------
    // Tile map
    // --------------------------
    inner class TileMap(private val tile: Int, val cols: Int, val rows: Int) {
        val grid: Array<CharArray>
        val pixelWidth = (cols * tile).toFloat()
        val pixelHeight = (rows * tile).toFloat()
        var playerStart = 0 to 0
        val spawnPoints = mutableListOf<Pair<Int,Int>>()
        var bossStart = (cols-6)*tile to (rows-4)*tile

        init {
            grid = generateMap(rows, cols)
            scanSpecials()
        }

        private fun scanSpecials() {
            for (y in 0 until rows) for (x in 0 until cols) {
                when (grid[y][x]) {
                    'P' -> playerStart = x*tile to (y-1)*tile
                    'E' -> spawnPoints.add(x*tile to (y-1)*tile)
                    'B' -> bossStart = x*tile to (y-2)*tile
                }
            }
        }

        fun isSolidAt(col: Int, row: Int): Boolean = if (row in 0 until rows && col in 0 until cols) grid[row][col] == '#' else false
        fun isOneWayAt(col: Int, row: Int): Boolean = if (row in 0 until rows && col in 0 until cols) grid[row][col] == '^' else false
        fun isSolidAtPx(px: Int, py: Int): Boolean = isSolidAt(px/tile, py/tile)

        fun collidesSolid(r: RectF): Boolean {
            val l = (r.left.toInt()/tile) - 1
            val t = (r.top.toInt()/tile) - 1
            val rr = (r.right.toInt()/tile) + 1
            val b = (r.bottom.toInt()/tile) + 1
            for (y in t..b) for (x in l..rr) if (isSolidAt(x,y)) {
                val rx = x*tile.toFloat(); val ry = y*tile.toFloat()
                if (RectF(rx,ry,rx+tile,ry+tile).let { it.left<r.right && it.right>r.left && it.top<r.bottom && it.bottom>r.top }) return true
            }
            return false
        }
        fun collidesOneWayFromAbove(r: RectF): Boolean {
            val l = (r.left.toInt()/tile) - 1
            val t = (r.top.toInt()/tile) - 1
            val rr = (r.right.toInt()/tile) + 1
            val b = (r.bottom.toInt()/tile) + 1
            for (y in t..b) for (x in l..rr) if (isOneWayAt(x,y)) {
                val rx = x*tile.toFloat(); val ry = y*tile.toFloat()
                val plat = RectF(rx,ry,rx+tile,ry+6f) // thin top surface
                if (r.bottom > plat.top && r.bottom < plat.bottom && r.right>plat.left && r.left<plat.right) return true
            }
            return false
        }

        fun draw(c: Canvas, p: Paint) {
            for (y in 0 until rows) for (x in 0 until cols) {
                val ch = grid[y][x]
                if (ch == '#') {
                    p.color = Color.rgb(80,82,100)
                    c.drawRect((x*tile).toFloat(), (y*tile).toFloat(), ((x+1)*tile).toFloat(), ((y+1)*tile).toFloat(), p)
                } else if (ch == '^') {
                    p.color = Color.rgb(150,150,180)
                    val top = RectF((x*tile).toFloat(), (y*tile).toFloat(), ((x+1)*tile).toFloat(), (y*tile+6).toFloat())
                    c.drawRect(top, p)
                }
            }
        }

        private fun generateMap(rows: Int, cols: Int): Array<CharArray> {
            val g = Array(rows) { CharArray(cols) { '.' } }
            // Base ground near bottom rows
            val groundY = rows - 2
            for (x in 0 until cols) g[groundY][x] = '#'
            for (x in 0 until cols step 7) g[groundY-1][x] = '#'

            // Sparse platforms '^'
            for (x in 10 until cols-10 step 14) {
                val y = 10 + (x % 3)
                for (i in 0..6) g[y][min(cols-1, x+i)] = '^'
            }
            // More platforms
            for (x in 50 until cols-20 step 25) {
                val y = 6 + (x % 4)
                for (i in 0..10) g[y][min(cols-1, x+i)] = '^'
            }

            // Place player start
            g[groundY-1][2] = 'P'
            // Enemies every ~80 cols
            for (x in 40 until cols-60 step 80) g[groundY-1][x] = 'E'
            for (x in 120 until cols-60 step 100) g[groundY-5][x] = 'E'
            // Boss near end
            g[groundY-1][cols-10] = 'B'
            return g
        }
    }
}
