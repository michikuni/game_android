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

/**
 * Tile-based level:
 *  - Loads ASCII map (assets/level1.txt or generates fallback)
 *  - Holds spawn points and solid/one-way queries
 *  - Performs AABB tile collisions for PhysicsBody (moveAndCollide)
 *  - Renders tiles and optional debug overlays
 *  - Manages 7-layer parallax background
 */
class TileMap(private val ctx: Context) {

    // ─────────────────────────────────────────────────────────────────────────────
    // Public world geometry (in tiles & pixels)
    // ─────────────────────────────────────────────────────────────────────────────
    val cols = Constants.WORLD_COLS
    val rows = Constants.WORLD_ROWS
    val tile = Constants.TILE
    val pixelWidth = (cols * tile).toFloat()
    val pixelHeight = (rows * tile).toFloat()

    // ─────────────────────────────────────────────────────────────────────────────
    // Map data & spawns
    // ─────────────────────────────────────────────────────────────────────────────
    val grid: Array<CharArray>        // ASCII cells ('.', '#', '^', markers)
    var playerStartX = tile * 2
    var playerStartY = tile * (rows - 3)
    var bossStartX = tile * (cols - 10)
    var bossStartY = tile * (rows - 3)

    /** Generic enemy spawn pads. Game picks which enemies to use. */
    val witchSpawns = mutableListOf<Pair<Int, Int>>()
    val skeletonSpawns = mutableListOf<Pair<Int, Int>>()
    val goblinSpawns = mutableListOf<Pair<Int, Int>>()

    /** Pickup spawns parsed from ASCII: hearts (H) and arrows (A). */
    val heartSpawns = mutableListOf<Pair<Int, Int>>()
    val ammoSpawns = mutableListOf<Pair<Int, Int>>()

    /** Provides grass/block bitmaps sized to one tile. */
    val tileset = TileSet(ctx)

    init {
        grid = loadAsciiOrGenerate(ctx)
        scanSpecials()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Debug overlays (toggled externally as needed)
    // ─────────────────────────────────────────────────────────────────────────────
    var debugShowGrid = false
    var debugShowSolids = false
    var debugShowIndices = false

    private val dbgGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        color = Color.argb(120, 120, 200, 255)
        strokeWidth = 1f
    }
    private val dbgSolidFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = false
        style = Paint.Style.FILL
        color = Color.argb(60, 255, 0, 0)
    }
    private val dbgSolidStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        color = Color.argb(180, 255, 50, 50)
        strokeWidth = 2f
    }
    private val dbgIndexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = (tile * 0.35f)
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Parallax background (7 layers)
    // ─────────────────────────────────────────────────────────────────────────────
    private data class ParallaxLayer(
        val bmp: Bitmap,
        val factor: Float,      // 1.0 = closest (moves fastest). Smaller = farther/slower.
        var offsetX: Float = 0f // computed each frame in updateBackground()
    )

    private var parallaxLayers: List<ParallaxLayer>? = null
    private var viewW = 0
    private var viewH = 0

    /**
     * Prepare background layers scaled to current SurfaceView height.
     * Call from SurfaceChanged (or when size changes).
     */
    fun initBackground(viewWidth: Int, viewHeight: Int) {
        if (parallaxLayers != null && viewWidth == viewW && viewHeight == viewH) return
        viewW = viewWidth
        viewH = viewHeight

        // Near → far movement factors
        val factors = listOf(1.00f, 0.85f, 0.70f, 0.55f, 0.40f, 0.28f, 0.16f)

        fun loadScaled(resId: Int): Bitmap {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val raw = BitmapFactory.decodeResource(ctx.resources, resId, opts)
            val scale = viewH / raw.height.toFloat()
            val scaledW = (raw.width * scale).toInt().coerceAtLeast(1)
            return raw.scale(scaledW, viewH)
        }

        // Replace these drawables with your actual 7 layers (nearest → farthest)
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

    /** Update horizontal offsets from camera X (wrap repeats mod layer width). */
    fun updateBackground(cam: com.example.game_android.game.core.Camera) {
        val layers = parallaxLayers ?: return
        for (layer in layers) {
            val w = layer.bmp.width.toFloat()
            var off = (cam.x * layer.factor) % w   // flip sign here if you want opposite direction
            if (off < 0f) off += w
            layer.offsetX = off
        }
    }

    /** Paint sky + far --> near layers, tiling horizontally. */
    fun drawBackground(c: Canvas) {
        val layers = parallaxLayers
        if (layers == null) {
            // Fallback: solid sky if background not initialized yet
            c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), Paint().apply {
                color = Color.rgb(19, 22, 34)
            })
            return
        }

        // Base sky
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), Paint().apply {
            color = Color.rgb(19, 22, 34)
        })

        // Far → near (near layers drawn last)
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

    // ─────────────────────────────────────────────────────────────────────────────
    // ASCII map loading & markers
    // ─────────────────────────────────────────────────────────────────────────────
    /** Load level from assets/level1.txt; if absent, generate a simple test map. */
    private fun loadAsciiOrGenerate(ctx: Context): Array<CharArray> {
        return try {
            ctx.assets.open("level1.txt").bufferedReader().use { br ->
                val lines = br.readLines().filter { it.isNotBlank() }
                Array(rows) { r ->
                    (if (r < lines.size) lines[r] else "")
                        .padEnd(cols, '.')
                        .toCharArray()
                }
            }
        } catch (_: Exception) {
            // Simple generator: ground line, some platforms/spawns/boss
            Array(rows) { CharArray(cols) { '.' } }.also { g ->
                val gy = rows - 1
                for (x in 0 until cols) g[gy][x] = '#'
                for (x in 0 until cols step 7) g[gy - 1][x] = '#'
                for (x in 10 until cols - 10 step 14) {
                    val y = 10 + (x % 3)
                    for (i in 0..6) g[y][kotlin.math.min(cols - 1, x + i)] = '^' // one-way
                }
                for (x in 50 until cols - 20 step 25) {
                    val y = 6 + (x % 4)
                    for (i in 0..10) g[y][kotlin.math.min(cols - 1, x + i)] = '^'
                }
                g[gy - 1][2] = 'P'                       // player
                for (x in 40 until cols - 60 step 80) g[gy - 1][x] = 'E'
                for (x in 120 until cols - 60 step 100) g[gy - 5][x] = 'E'
                g[gy - 1][cols - 10] = 'B'               // boss
            }
        }
    }

    /** Parse markers (P,E,B,W,S,G,H,A) and populate spawn lists; clear marker tiles. */
    private fun scanSpecials() {
        for (y in 0 until rows) for (x in 0 until cols) {
            when (grid[y][x]) {
                'P' -> {
                    playerStartX = x * tile
                    playerStartY = (y - 1) * tile
                    grid[y][x] = '.'
                }

                'B' -> {
                    bossStartX = x * tile
                    bossStartY = (y - 2) * tile
                    grid[y][x] = '.'
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

                'H' -> {
                    heartSpawns += (x * tile) to ((y - 1) * tile); grid[y][x] = '.'
                }

                'A' -> {
                    ammoSpawns += (x * tile) to ((y - 1) * tile); grid[y][x] = '.'
                }
            }
        }
    }


    // ─────────────────────────────────────────────────────────────────────────────
    // Tile type queries (ASCII → semantics)
    // ─────────────────────────────────────────────────────────────────────────────
    private fun isSolidChar(ch: Char): Boolean = (ch == '#')

    fun isSolidAt(col: Int, row: Int): Boolean =
        row in 0 until rows && col in 0 until cols && isSolidChar(grid[row][col])

    fun isOneWayAt(col: Int, row: Int): Boolean =
        row in 0 until rows && col in 0 until cols && grid[row][col] == '^'

    fun isSolidAtPx(px: Int, py: Int): Boolean = isSolidAt(px / tile, py / tile)

    /**
     * Cheap 4-corner AABB check against solids.
     * Good enough for small tiles and fast broad-phase rejections.
     */
    fun isSolidAtPxRect(r: RectF): Boolean {
        return isSolidAtPx(r.left.toInt(), r.top.toInt()) ||
                isSolidAtPx(r.right.toInt(), r.top.toInt()) ||
                isSolidAtPx(r.left.toInt(), r.bottom.toInt()) ||
                isSolidAtPx(r.right.toInt(), r.bottom.toInt())
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Collision & movement: AABB vs tiles (solids + one-way)
    // ─────────────────────────────────────────────────────────────────────────────
    /**
     * Move an entity by (vx, vy) with tile collisions.
     * - Horizontal pass clamps against solids and zeroes vx on hit.
     * - Vertical pass resolves against solids and one-way platforms ('^').
     *   Landing on ground sets e.canJump = true.
     */
    fun moveAndCollide(e: com.example.game_android.game.entities.PhysicsBody) {
        // Horizontal pass
        var nx = e.x + e.vx
        val hb = RectF(nx, e.y, nx + e.w, e.y + e.h)
        if (collidesSolid(hb)) {
            nx = if (e.vx > 0) {
                ((hb.right.toInt() / tile) * tile - e.w - 1).toFloat() // push left
            } else {
                ((hb.left.toInt() / tile + 1) * tile).toFloat()         // push right
            }
            e.vx = 0f
        }
        e.x = nx

        // Vertical pass
        var ny = e.y + e.vy
        val vb = RectF(e.x, ny, e.x + e.w, ny + e.h)

        val hitSolid = collidesSolid(vb)
        val hitOneWayDown = e.vy >= 0 && collidesOneWayFromAbove(vb)

        if (hitSolid || hitOneWayDown) {
            if (e.vy > 0) {
                // Landing: snap to tile top, allow jump
                ny = (vb.bottom - (vb.bottom % tile)).toInt().toFloat() - e.h - 0.1f
                e.canJump = true
            } else if (e.vy < 0 && hitSolid) {
                // Hitting ceiling: snap below tile
                ny = (vb.top - (vb.top % tile) + tile).toInt().toFloat()
            }
            e.vy = 0f
        }
        e.y = ny
    }

    /** Narrow-phase solid collision: scan nearby cells and test overlap. */
    private fun collidesSolid(r: RectF): Boolean {
        val l = (r.left.toInt() / tile) - 1
        val t = (r.top.toInt() / tile) - 1
        val rr = (r.right.toInt() / tile) + 1
        val b = (r.bottom.toInt() / tile) + 1
        for (y in t..b) for (x in l..rr) if (isSolidAt(x, y)) {
            val cell = RectF(
                x * tile.toFloat(),
                y * tile.toFloat(),
                (x + 1) * tile.toFloat(),
                (y + 1) * tile.toFloat()
            )
            if (cell.left < r.right && cell.right > r.left && cell.top < r.bottom && cell.bottom > r.top) {
                return true
            }
        }
        return false
    }

    /**
     * One-way platforms: collide only when falling from above.
     * Uses a thin rectangle at tile top (tile height ~6px) to avoid snagging.
     */
    private fun collidesOneWayFromAbove(r: RectF): Boolean {
        val l = (r.left.toInt() / tile) - 1
        val t = (r.top.toInt() / tile) - 1
        val rr = (r.right.toInt() / tile) + 1
        val b = (r.bottom.toInt() / tile) + 1
        for (y in t..b) for (x in l..rr) if (isOneWayAt(x, y)) {
            val rx = x * tile.toFloat()
            val ry = y * tile.toFloat()
            val plat = RectF(rx, ry, rx + tile, ry + 6f) // 6 px thick top band
            val footInside = r.bottom > plat.top && r.bottom < plat.bottom
            val horizontalOverlap = r.right > plat.left && r.left < plat.right
            if (footInside && horizontalOverlap) return true
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rendering: tiles & debug
    // ─────────────────────────────────────────────────────────────────────────────
    /**
     * Draw visible tiles. For '#': draw grass if above is empty, else a solid block.
     * (Extend this to support more glyphs if you add more tile types.)
     */
    fun drawTiles(c: Canvas) {
        for (y in 0 until rows) for (x in 0 until cols) {
            if (grid[y][x] == '#') {
                val aboveEmpty = (y == 0) || grid[y - 1][x] != '#'
                val bmp = if (aboveEmpty) tileset.grass else tileset.block
                c.drawBitmap(bmp, (x * tile).toFloat(), (y * tile).toFloat(), null)
            }
        }
    }

    /** Draw grid/solids/indices only for tiles inside the current camera view. */
    fun drawDebugTiles(c: Canvas, camX: Float, camY: Float, viewW: Int, viewH: Int) {
        if (!debugShowGrid && !debugShowSolids && !debugShowIndices) return
        val (c0, c1, r0, r1) = visibleRange(camX, camY, viewW, viewH)

        for (r in r0..r1) for (cx in c0..c1) {
            val left = (cx * tile).toFloat()
            val top = (r * tile).toFloat()
            val right = left + tile
            val bottom = top + tile
            val ch = grid[r][cx]

            if (debugShowSolids && isSolidChar(ch)) {
                c.drawRect(left, top, right, bottom, dbgSolidFill)
                c.drawRect(left, top, right, bottom, dbgSolidStroke)
            }
            if (debugShowGrid) {
                c.drawRect(left, top, right, bottom, dbgGridPaint)
            }
            if (debugShowIndices) {
                c.drawText("$cx,$r", left + 4f, top + dbgIndexPaint.textSize + 2f, dbgIndexPaint)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Small helpers
    // ─────────────────────────────────────────────────────────────────────────────
    /** Clamp camera-aligned visible tile range (inclusive). */
    private fun visibleRange(camX: Float, camY: Float, viewW: Int, viewH: Int): IntArray {
        val c0 = (camX / tile).toInt().coerceIn(0, cols - 1)
        val c1 = ((camX + viewW) / tile).toInt().coerceIn(0, cols - 1)
        val r0 = (camY / tile).toInt().coerceIn(0, rows - 1)
        val r1 = ((camY + viewH) / tile).toInt().coerceIn(0, rows - 1)
        return intArrayOf(c0, c1, r0, r1)
    }
}