package com.example.game_android.game.world

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.scale
import com.example.game_android.R
import com.example.game_android.game.core.Constants

class TileMap(private val ctx: Context) {
    val cols = Constants.WORLD_COLS
    val rows = Constants.WORLD_ROWS
    val tile = Constants.TILE
    val pixelWidth = (cols * tile).toFloat()
    val pixelHeight = (rows * tile).toFloat()
    val grid: Array<CharArray>
    var playerStartX = tile * 2
    var playerStartY = tile * (rows - 3)
    val spawnPoints = mutableListOf<Pair<Int, Int>>()
    val witchSpawns = mutableListOf<Pair<Int, Int>>()
    val skeletonSpawns = mutableListOf<Pair<Int, Int>>()
    val goblinSpawns = mutableListOf<Pair<Int, Int>>()
    var bossStartX = tile * (cols - 10)
    var bossStartY = tile * (rows - 3)

    val heartSpawns = mutableListOf<Pair<Int, Int>>()
    val ammoSpawns = mutableListOf<Pair<Int, Int>>()

    val tileset = TileSet(ctx)

    init {
        grid = loadAsciiOrGenerate(ctx)
        scanSpecials()
    }

    // -------------------------------
    // --- NEW: Parallax background ---
    // -------------------------------

    private data class ParallaxLayer(
        val bmp: Bitmap,
        val factor: Float,    // 1.0 = fastest (closest), smaller = farther/slower
        var offsetX: Float = 0f
    )

    private var parallaxLayers: List<ParallaxLayer>? = null
    private var viewW: Int = 0
    private var viewH: Int = 0

    // --- Debug toggles (flip these at runtime as you like) ---
    var debugShowGrid = false
    var debugShowSolids = false
    var debugShowIndices = false

    // --- Debug paints ---
    private val dbgGridPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(120, 120, 200, 255) // cyan-ish
        strokeWidth = 1f
        isAntiAlias = false
    }

    private val dbgSolidFill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 255, 0, 0) // translucent red
        isAntiAlias = false
    }

    private val dbgSolidStroke = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(180, 255, 50, 50)
        strokeWidth = 2f
        isAntiAlias = false
    }

    private val dbgIndexPaint = Paint().apply {
        color = Color.WHITE
        textSize = (tile * 0.35f)
        isAntiAlias = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }


    private fun visibleRange(camX: Float, camY: Float, viewW: Int, viewH: Int): IntArray {
        val c0 = (camX / tile).toInt().coerceIn(0, cols - 1)
        val c1 = ((camX + viewW) / tile).toInt().coerceIn(0, cols - 1)
        val r0 = (camY / tile).toInt().coerceIn(0, rows - 1)
        val r1 = ((camY + viewH) / tile).toInt().coerceIn(0, rows - 1)
        return intArrayOf(c0, c1, r0, r1)
    }

    fun drawDebugTiles(c: Canvas, camX: Float, camY: Float, viewW: Int, viewH: Int) {
        if (!debugShowGrid && !debugShowSolids && !debugShowIndices) return

        val (c0, c1, r0, r1) = visibleRange(camX, camY, viewW, viewH)

        // Draw per-tile overlays just for visible range
        for (r in r0..r1) {
            for (cix in c0..c1) {
                val left = (cix * tile).toFloat()
                val top = (r * tile).toFloat()
                val right = left + tile
                val bottom = top + tile

                val ch = grid[r][cix]

                // Solid tiles: semi-transparent fill + outline
                if (debugShowSolids && isSolidChar(ch)) {
                    c.drawRect(left, top, right, bottom, dbgSolidFill)
                    c.drawRect(left, top, right, bottom, dbgSolidStroke)
                }

                // Grid lines
                if (debugShowGrid) {
                    c.drawRect(left, top, right, bottom, dbgGridPaint)
                }

                // Row/Col indices in the corner
                if (debugShowIndices) {
                    c.drawText(
                        "$cix,$r",
                        left + 4f,
                        top + dbgIndexPaint.textSize + 2f,
                        dbgIndexPaint
                    )
                }
            }
        }
    }

    // Helper: define what counts as solid in your ASCII map
    private fun isSolidChar(ch: Char): Boolean {
        return when (ch) {
            '#', 'B', 'G' -> true     // sample: Block, Ground, etc. Adjust to your set
            else -> false
        }
    }


    /**
     * Call once you know the SurfaceView size (e.g., from GameView.surfaceChanged).
     */
    fun initBackground(viewWidth: Int, viewHeight: Int) {
        // Avoid reloading if size hasn’t changed
        if (parallaxLayers != null && viewWidth == viewW && viewHeight == viewH) return
        viewW = viewWidth
        viewH = viewHeight

        // Factors from nearest → farthest (tweak to taste)
        val factors = listOf(1.00f, 0.85f, 0.70f, 0.55f, 0.40f, 0.28f, 0.16f)

        // Helper for loading + scaling a bitmap to the view height
        fun loadScaled(resId: Int): Bitmap {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565 // memory-friendly
            }
            val raw = BitmapFactory.decodeResource(ctx.resources, resId, opts)
            val scale = viewH / raw.height.toFloat()
            val scaledW = (raw.width * scale).toInt().coerceAtLeast(1)
            return raw.scale(scaledW, viewH)
        }

        // Replace with your actual R.drawable.* names (nearest → farthest)
        val bitmaps = listOf(
            loadScaled(R.drawable.bg_layer_1),
            loadScaled(R.drawable.bg_layer_2),
            loadScaled(R.drawable.bg_layer_3),
            loadScaled(R.drawable.bg_layer_4),
            loadScaled(R.drawable.bg_layer_5),
            loadScaled(R.drawable.bg_layer_6),
            loadScaled(R.drawable.bg_layer_7),
        )

        parallaxLayers = bitmaps.zip(factors) { bmp, f -> ParallaxLayer(bmp, f) }
    }

    /**
     * Update background offsets from the camera X position.
     * If direction feels inverted, flip the sign in the formula below.
     */
    fun updateBackground(cam: com.example.game_android.game.core.Camera) {
        val layers = parallaxLayers ?: return
        for (layer in layers) {
            val w = layer.bmp.width.toFloat()
            // If moving camera right should make near layers move left on screen, keep negative sign.
//        var off = (-cam.x * layer.factor) % w
            var off = (cam.x * layer.factor) % w  // instead of -cam.x
            if (off < 0f) off += w
            layer.offsetX = off
        }
    }

    /**
     * Draw parallax background; call before drawing tiles/entities.
     */
    fun drawBackground(c: Canvas) {
        val layers = parallaxLayers
        if (layers == null) {
            // Fallback: simple sky color if not initialized yet
            val p = Paint()
            p.color = Color.rgb(19, 22, 34)
            c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), p)
            return
        }

        // Optional: sky base behind all layers
        val p = Paint()
        p.color = Color.rgb(19, 22, 34)
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), p)

        // Draw far → near so closer layers overlap
        for (i in layers.indices.reversed()) {
            val layer = layers[i]
            val bmp = layer.bmp
            val bmpW = bmp.width
            var drawX = -layer.offsetX
            while (drawX < c.width) {
                c.drawBitmap(bmp, drawX, 0f, null)
                drawX += bmpW
            }
        }
    }

    // -------------------------------
    // --- End Parallax background  ---
    // -------------------------------

    private fun loadAsciiOrGenerate(ctx: Context): Array<CharArray> {
        return try {
            ctx.assets.open("level1.txt").bufferedReader().use { br ->
                val lines = br.readLines().filter { it.isNotBlank() }
                Array(rows) { r ->
                    (if (r < lines.size) lines[r] else "").padEnd(cols, '.').toCharArray()
                }
            }
        } catch (_: Exception) {
            Array(rows) { CharArray(cols) { '.' } }.also { g ->
                val gy = rows - 1
                for (x in 0 until cols) g[gy][x] = '#'
                for (x in 0 until cols step 7) g[gy - 1][x] = '#'
                for (x in 10 until cols - 10 step 14) {
                    val y = 10 + (x % 3)
                    for (i in 0..6) g[y][kotlin.math.min(cols - 1, x + i)] = '^'
                }
                for (x in 50 until cols - 20 step 25) {
                    val y = 6 + (x % 4)
                    for (i in 0..10) g[y][kotlin.math.min(cols - 1, x + i)] = '^'
                }
                g[gy - 1][2] = 'P'
                for (x in 40 until cols - 60 step 80) g[gy - 1][x] = 'E'
                for (x in 120 until cols - 60 step 100) g[gy - 5][x] = 'E'
                g[gy - 1][cols - 10] = 'B'
            }
        }
    }

    private fun scanSpecials() {
        for (y in 0 until rows) for (x in 0 until cols) {
            when (grid[y][x]) {
                'P' -> {
                    playerStartX = x * tile; playerStartY = (y - 1) * tile
                }

                'E' -> spawnPoints += (x * tile) to (y - 1) * tile
                'B' -> {
                    bossStartX = x * tile; bossStartY = (y - 2) * tile
                }

                'W' -> {
                    witchSpawns += (x * tile) to (y * tile); grid[y][x] = '.'
                }

                'S' -> {
                    skeletonSpawns += (x * tile) to (y * tile); grid[y][x] = '.'
                }

                'G' -> {
                    goblinSpawns += (x * tile) to (y * tile); grid[y][x] = '.'
                }

                // NEW:
                'H' -> {  // Heart potion pickup
                    heartSpawns += (x * tile) to ((y - 1) * tile)
                    grid[y][x] = '.'
                }

                'A' -> {  // Arrow pickup
                    ammoSpawns += (x * tile) to ((y - 1) * tile)
                    grid[y][x] = '.'
                }
            }
        }
    }

    fun isSolidAt(col: Int, row: Int) =
        row in 0 until rows && col in 0 until cols && grid[row][col] == '#'

    fun isOneWayAt(col: Int, row: Int) =
        row in 0 until rows && col in 0 until cols && grid[row][col] == '^'

    fun isSolidAtPx(px: Int, py: Int) = isSolidAt(px / tile, py / tile)

    fun isSolidAtPxRect(r: RectF): Boolean {
        return isSolidAtPx(r.left.toInt(), r.top.toInt()) ||
                isSolidAtPx(r.right.toInt(), r.top.toInt()) ||
                isSolidAtPx(r.left.toInt(), r.bottom.toInt()) ||
                isSolidAtPx(r.right.toInt(), r.bottom.toInt())
    }

    fun moveAndCollide(e: com.example.game_android.game.entities.PhysicsBody) {
        var nx = e.x + e.vx
        val hb = RectF(nx, e.y, nx + e.w, e.y + e.h)
        if (collidesSolid(hb)) {
            if (e.vx > 0) nx = ((hb.right.toInt() / tile) * tile - e.w - 1).toFloat()
            else nx = ((hb.left.toInt() / tile + 1) * tile).toFloat()
            e.vx = 0f
        }
        e.x = nx

        var ny = e.y + e.vy
        val vb = RectF(e.x, ny, e.x + e.w, ny + e.h)
        val hitSolid = collidesSolid(vb)
        val hitOneWayDown = e.vy >= 0 && collidesOneWayFromAbove(vb)
        if (hitSolid || hitOneWayDown) {
            if (e.vy > 0) {
                ny = (vb.bottom - (vb.bottom % tile)).toInt().toFloat() - e.h - 0.1f
                e.canJump = true
            } else if (e.vy < 0 && hitSolid) {
                ny = (vb.top - (vb.top % tile) + tile).toInt().toFloat()
            }
            e.vy = 0f
        }
        e.y = ny
    }

    private fun collidesSolid(r: RectF): Boolean {
        val l = (r.left.toInt() / tile) - 1
        val t = (r.top.toInt() / tile) - 1
        val rr = (r.right.toInt() / tile) + 1
        val b = (r.bottom.toInt() / tile) + 1
        for (y in t..b) for (x in l..rr) if (isSolidAt(x, y)) {
            val rx = x * tile.toFloat()
            val ry = y * tile.toFloat()
            val cell = RectF(rx, ry, rx + tile, ry + tile)
            if (cell.left < r.right && cell.right > r.left && cell.top < r.bottom && cell.bottom > r.top) return true
        }
        return false
    }

    private fun collidesOneWayFromAbove(r: RectF): Boolean {
        val l = (r.left.toInt() / tile) - 1
        val t = (r.top.toInt() / tile) - 1
        val rr = (r.right.toInt() / tile) + 1
        val b = (r.bottom.toInt() / tile) + 1
        for (y in t..b) for (x in l..rr) if (isOneWayAt(x, y)) {
            val rx = x * tile.toFloat()
            val ry = y * tile.toFloat()
            val plat = RectF(rx, ry, rx + tile, ry + 6f)
            if (r.bottom > plat.top && r.bottom < plat.bottom && r.right > plat.left && r.left < plat.right) return true
        }
        return false
    }

    fun drawTiles(c: Canvas) {
        for (y in 0 until rows) for (x in 0 until cols) {
            if (grid[y][x] == '#') {
                val aboveEmpty = y == 0 || grid[y - 1][x] != '#'
                val bmp = if (aboveEmpty) tileset.grass else tileset.block
                c.drawBitmap(bmp, (x * tile).toFloat(), (y * tile).toFloat(), null)
            }
        }
    }
}