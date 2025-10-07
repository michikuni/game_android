package com.example.game_android.game.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.example.game_android.R
import com.example.game_android.game.entities.Player
import com.example.game_android.game.core.InputController
import com.example.game_android.game.core.InputController.BtnKind
import com.example.game_android.game.world.GameState
import androidx.core.graphics.withRotation
import androidx.core.graphics.toColorInt
import com.example.game_android.game.util.BitmapUtils

class HudRenderer(private val input: InputController, private val context: Context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // --- Slider internals (pause menu) ---
    private val sliderTrackMusic = RectF()
    private val sliderTrackSfx   = RectF()
    private val sliderKnobRadius = dp(10f)

    // Drag state
    private enum class SliderKind { NONE, MUSIC, SFX }
    private var dragging: SliderKind = SliderKind.NONE

    private fun drawSlider(
        c: Canvas,
        track: RectF,
        label: String,
        value01: Float,       // 0..1
    ) {
        // Label
        t.textAlign = Paint.Align.LEFT
        t.textSize = dp(16f)
        t.color = Color.WHITE
        c.drawText(label, track.left, track.top - dp(8f), t)

        // Track
        p.shader = null
        p.color = Color.argb(160, 220, 220, 230)
        c.drawRoundRect(track, dp(5f), dp(5f), p)

        // Fill
        val fill = RectF(track.left, track.top, track.left + track.width() * value01.coerceIn(0f, 1f), track.bottom)
        p.color = Color.argb(220, 120, 160, 255)
        c.drawRoundRect(fill, dp(5f), dp(5f), p)

        // Knob
        val kx = fill.right
        val ky = track.centerY()
        p.color = Color.WHITE
        c.drawCircle(kx, ky, sliderKnobRadius, p)
        p.color = Color.DKGRAY
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(2f)
        c.drawCircle(kx, ky, sliderKnobRadius, p)
        p.style = Paint.Style.FILL
    }

    private fun valueFromTrack(track: RectF, x: Float): Float {
        if (track.width() <= 0f) return 0f
        return ((x - track.left) / track.width()).coerceIn(0f, 1f)
    }

    // --- Overlay rects ---
    private val pauseContinueRect = RectF()
    private val pauseExitRect = RectF()
    private val gameOverRetryRect = RectF()
    private val gameOverExitRect = RectF()
    private val victoryExitRect = RectF()

    // Drawable cache (vector + bitmap)
    private val drCache = mutableMapOf<Int, Drawable?>()
    private fun dr(@DrawableRes id: Int): Drawable? =
        drCache.getOrPut(id) { AppCompatResources.getDrawable(context, id) }

    // Paint overlay dùng chung
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0) // tối nhẹ khi nhấn
        style = Paint.Style.FILL
    }

    private val topMuteRect = RectF()

    private fun dp(v: Float) = v * context.resources.displayMetrics.density

    private val heartBmp: Bitmap by lazy {
        BitmapUtils.decodePixelArt(context, R.drawable.heart) // heart.png -> R.drawable.heart
    }
    private val heartSrcTrim: Rect by lazy {
        com.example.game_android.game.util.BitmapUtils.computeOpaqueBounds(heartBmp)
            ?: Rect(0, 0, heartBmp.width, heartBmp.height)
    }
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false // crisp pixel art
    }
    private val heartGreyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        // Tint to dark grey while preserving alpha
        colorFilter = PorterDuffColorFilter(Color.DKGRAY,
            PorterDuff.Mode.SRC_IN)
    }

    // --- Hearts (bitmap sprite with transparent trim) ---
    val heartSize = dp(32f)         // HUD size for each heart (scaled from 64px source)
    val heartGap  = dp(8f)          // spacing between hearts
    val heartsTop = dp(16f)
    val heartsLeftStart = dp(16f)

    // Arrow sprite caches (trim like Arrow.kt)
    private val arrowBmp: Bitmap by lazy {
        BitmapUtils.decodePixelArt(context, R.drawable.arrow01_100x100)
    }
    private val arrowSrcTrim: Rect by lazy {
        com.example.game_android.game.util.BitmapUtils.computeOpaqueBounds(arrowBmp)
            ?: Rect(0, 0, arrowBmp.width, arrowBmp.height)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false // crisp pixel art
    }
    private val arrowGreyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false

        val tintColor = "#43242F".toColorInt()

        colorFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BlendModeColorFilter(tintColor, BlendMode.SRC_IN)   // API 29+
        } else {
            PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        }
    }

    fun drawHud(
        c: Canvas,
        player: Player,
        ammoCapacity: Int,
        ammoCount: Int,
        maxHp: Int = 3,
        score: Int = 0,
        highScore: Int = 0,
    ) {
        // --- Hearts ---
        repeat(maxHp) { i ->
            val left = heartsLeftStart + i * (heartSize + heartGap)
            val dst = RectF(left, heartsTop, left + heartSize, heartsTop + heartSize)

            // choose paint: colored for remaining hp, grey for lost hp
            val paintForThis = if (i < player.hp) heartPaint else heartGreyPaint

            // draw only the trimmed opaque area of the bitmap, scaled into dst
            c.drawBitmap(heartBmp, heartSrcTrim, dst, paintForThis)
        }

        // --- Ammo (arrows) under hearts ---
        val ammoSize = dp(24f)          // a bit smaller than hearts
        val ammoGap  = dp(0f)
        val ammoTop  = heartsTop + heartSize + dp(6f)
        val ammoLeftStart = heartsLeftStart

        repeat(ammoCapacity) { i ->
            val left = ammoLeftStart + i * (ammoSize + ammoGap)
            val dst  = RectF(left, ammoTop, left + ammoSize, ammoTop + ammoSize)

            // grey if this slot is beyond current ammo
            val paintForThis = if (i < ammoCount) arrowPaint else arrowGreyPaint

            // the source points RIGHT; rotate +90° around the dst center so it points DOWN
            c.withRotation(90f, dst.centerX(), dst.centerY()) {
                drawBitmap(arrowBmp, arrowSrcTrim, dst, paintForThis)
            }
        }

        t.textAlign = Paint.Align.LEFT
        t.textSize = dp(16f)
        t.color = Color.WHITE
        val scoreY = ammoTop + ammoSize + dp(16f)
        c.drawText("Score: $score", heartsLeftStart, scoreY, t)
        c.drawText("High:  $highScore", heartsLeftStart, scoreY + dp(18f), t)

        // --- HUD buttons ---
        input.buttons().forEach { spec ->
            val resId = when (spec.kind) {
                BtnKind.Left  -> R.drawable.arrow_circle_left_24px
                BtnKind.Right -> R.drawable.arrow_circle_right_24px
                BtnKind.Jump  -> R.drawable.jump_24px
                BtnKind.Fire  -> R.drawable.bow_arrow
                BtnKind.Pause -> R.drawable.pause_24px
                BtnKind.Melee -> R.drawable.sword_cross
            }
            val d = dr(resId)

            if (isCircle(spec.kind)) {
                drawCircleButton(c, spec.rect, d)   // nút tròn
            } else {
                drawSquareButton(c, spec.rect, d)  // nút vuông nhỏ
            }

            drawPressedOverlay(c, spec.kind, spec.rect)
        }
    }

    // === Helpers ===

    private fun isCircle(kind: BtnKind): Boolean =
        kind == BtnKind.Left || kind == BtnKind.Right || kind == BtnKind.Jump || kind == BtnKind.Fire || kind == BtnKind.Melee

    private fun asCircleRect(r: RectF): RectF {
        val size = minOf(r.width(), r.height())
        val cx = r.centerX()
        val cy = r.centerY()
        return RectF(cx - size/2f, cy - size/2f, cx + size/2f, cy + size/2f)
    }

    private fun isPressed(kind: BtnKind): Boolean = when (kind) {
        BtnKind.Left  -> input.left
        BtnKind.Right -> input.right
        BtnKind.Jump  -> input.jump
        BtnKind.Fire  -> input.fire
        BtnKind.Melee -> input.melee
        else          -> false
    }

    private fun drawCircleButton(
        c: Canvas,
        rect: RectF,
        drawable: Drawable?,
        bgColor: Int = Color.TRANSPARENT,
        iconPaddingPx: Float = 8f
    ) {
        val rr = asCircleRect(rect)
        val radius = rr.width() / 2f

        // nền tròn
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        c.drawCircle(rr.centerX(), rr.centerY(), radius, fill)

        // icon
        drawable?.setBounds(
            (rr.left + iconPaddingPx).toInt(),
            (rr.top + iconPaddingPx).toInt(),
            (rr.right - iconPaddingPx).toInt(),
            (rr.bottom - iconPaddingPx).toInt()
        )
        drawable?.draw(c)
    }

    private fun drawSquareButton(
        c: Canvas,
        rect: RectF,
        drawable: Drawable?,
        bgColor: Int = Color.TRANSPARENT,
        iconPaddingPx: Float = 6f
    ) {
        // nền vuông
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        c.drawRect(rect, fill)

        // icon
        drawable?.setBounds(
            (rect.left + iconPaddingPx).toInt(),
            (rect.top + iconPaddingPx).toInt(),
            (rect.right - iconPaddingPx).toInt(),
            (rect.bottom - iconPaddingPx).toInt()
        )
        drawable?.draw(c)
    }

    private fun drawPressedOverlay(c: Canvas, kind: BtnKind, rect: RectF) {
        if (!isPressed(kind)) return
        if (isCircle(kind)) {
            val rr = asCircleRect(rect)
            val radius = rr.width() / 2f
            c.drawCircle(rr.centerX(), rr.centerY(), radius, overlayPaint)
        } else {
            c.drawRect(rect, overlayPaint)
        }
    }

    // --- Overlays for pause/gameover/victory ---
    enum class Action { PauseToggle, Exit, Continue, Retry, BackToMenu }

    fun drawOverlays(
        c: Canvas,
        s: GameState,
        bgVol: Float,
        sfxVol: Float,
        score: Int,
        highScore: Int
    ) {
        Log.d("HudRenderer", "drawOverlays: paused=${s.paused} gameOver=${s.gameOver} victory=${s.victory} bgVol=${bgVol} sfxVol=${sfxVol}")
        clearOverlayRects()

        if (!s.anyOverlay()) return

        // dim screen
        p.color = Color.argb(180, 0, 0, 0)
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), p)

        t.textAlign = Paint.Align.CENTER
        t.textSize = 64f
        t.color = Color.WHITE

        if (s.paused) {
            c.drawText("PAUSED", c.width/2f, c.height*0.30f, t)
            val bw = c.width*0.32f; val bh = c.height*0.11f

            // Buttons under title
            pauseContinueRect.set(
                c.width/2f - bw/2, c.height*0.40f,
                c.width/2f + bw/2, c.height*0.40f + bh
            )
            pauseExitRect.set(
                c.width/2f - bw/2, pauseContinueRect.bottom + dp(16f),
                c.width/2f + bw/2, pauseContinueRect.bottom + dp(16f) + bh
            )
            drawButtonFromText(c, pauseContinueRect, "Continue")
            drawButtonFromText(c, pauseExitRect, "Exit")

            // Sliders (on the left side area)
            val sliderWidth  = c.width * 0.46f
            val sliderHeight = dp(18f)
            val sliderLeft   = c.width * 0.27f
            val sliderGap    = dp(24f)
            val firstTop     = pauseExitRect.bottom + dp(36f)

            sliderTrackMusic.set(sliderLeft, firstTop, sliderLeft + sliderWidth, firstTop + sliderHeight)
            sliderTrackSfx.set(sliderLeft, sliderTrackMusic.bottom + sliderGap, sliderLeft + sliderWidth, sliderTrackMusic.bottom + sliderGap + sliderHeight)

            drawSlider(c, sliderTrackMusic, "Music", bgVol)
            drawSlider(c, sliderTrackSfx,   "SFX",   sfxVol)

            return
        }

        if (s.gameOver) {
            c.drawText("GAME OVER", c.width/2f, c.height*0.35f, t)
            val bw = c.width*0.3f; val bh = c.height*0.12f
            gameOverRetryRect.set(
                c.width/2f - bw/2, c.height*0.45f,
                c.width/2f + bw/2, c.height*0.45f + bh
            )
            gameOverExitRect.set(
                c.width/2f - bw/2, gameOverRetryRect.bottom + 24f,
                c.width/2f + bw/2, gameOverRetryRect.bottom + 24f + bh
            )
            drawButtonFromText(c, gameOverRetryRect, "Retry")
            drawButtonFromText(c, gameOverExitRect, "Exit")
            return
        }

        if (s.victory) {
            c.drawText("VICTORY!", c.width/2f, c.height*0.30f, t)

            // Score lines
            t.textSize = 42f
            t.textAlign = Paint.Align.CENTER
            c.drawText("Score: $score",      c.width/2f, c.height*0.38f, t)
            c.drawText("High Score: $highScore", c.width/2f, c.height*0.44f, t)

            val bw = c.width*0.4f; val bh = c.height*0.12f
            victoryExitRect.set(
                c.width/2f - bw/2, c.height*0.55f,
                c.width/2f + bw/2, c.height*0.55f + bh
            )
            drawButtonFromText(c, victoryExitRect, "Back to Menu")
            return
        }

    }

    fun handleTouch(
        action: Int,
        x: Float,
        y: Float,
        state: GameState,
        onPauseToggle: () -> Unit,
        onExit: () -> Unit,
        onContinue: () -> Unit,
        onRetry: () -> Unit,
        onBackToMenu: () -> Unit,
        onSetBgmVolume: (Float) -> Unit,  // 0..1
        onSetSfxVolume: (Float) -> Unit   // 0..1
    ): Boolean {
        // GAME OVER overlay
        if (state.gameOver) {
            if (action == MotionEvent.ACTION_UP) {
                if (gameOverRetryRect.contains(x,y)) { onRetry(); return true }
                if (gameOverExitRect.contains(x,y)) { onExit(); return true }
            }
            return false
        }

        // VICTORY overlay
        if (state.victory) {
            if (action == MotionEvent.ACTION_UP) {
                if (victoryExitRect.contains(x,y)) { onBackToMenu(); return true }
            }
            return false
        }

        // PAUSED overlay (sliders + buttons)
        if (state.paused) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    // Buttons first
                    if (pauseContinueRect.contains(x,y)) { dragging = SliderKind.NONE; return true }
                    if (pauseExitRect.contains(x,y))     { dragging = SliderKind.NONE; return true }

                    // Start dragging if touching a slider
                    when {
                        sliderTrackMusic.contains(x, y) -> {
                            dragging = SliderKind.MUSIC
                            onSetBgmVolume(valueFromTrack(sliderTrackMusic, x))
                            return true
                        }
                        sliderTrackSfx.contains(x, y) -> {
                            dragging = SliderKind.SFX
                            onSetSfxVolume(valueFromTrack(sliderTrackSfx, x))
                            return true
                        }
                        else -> {
                            dragging = SliderKind.NONE
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    when (dragging) {
                        SliderKind.MUSIC -> {
                            onSetBgmVolume(valueFromTrack(sliderTrackMusic, x))
                            return true
                        }
                        SliderKind.SFX -> {
                            onSetSfxVolume(valueFromTrack(sliderTrackSfx, x))
                            return true
                        }
                        else -> { /* no-op */ }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // End drag, also handle button activations on UP
                    val wasDragging = dragging != SliderKind.NONE
                    dragging = SliderKind.NONE

                    // If it was not a drag, treat as button taps
                    if (!wasDragging) {
                        if (pauseContinueRect.contains(x,y)) { onContinue(); return true }
                        if (pauseExitRect.contains(x,y))     { onExit(); return true }
                        // Tap anywhere on the track also sets volume on UP (nice to have)
                        if (sliderTrackMusic.contains(x,y)) {
                            onSetBgmVolume(valueFromTrack(sliderTrackMusic, x)); return true
                        }
                        if (sliderTrackSfx.contains(x,y)) {
                            onSetSfxVolume(valueFromTrack(sliderTrackSfx, x)); return true
                        }
                    }
                }
            }
            return dragging != SliderKind.NONE
        }

        // NORMAL gameplay (no sliders here)
        if (action == MotionEvent.ACTION_UP && input.isPauseHit(x,y)) {
            onPauseToggle(); return true
        }

        return false
    }


    private fun drawButtonFromText(c: Canvas, r: RectF, label: String) {
        p.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            Color.argb(160, 60, 60, 70),
            Color.argb(160, 30, 30, 40),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(r, 20f, 20f, p)
        p.shader = null
        t.textAlign = Paint.Align.CENTER
        t.textSize = minOf(r.width(), r.height()) * 0.5f
        c.drawText(label, r.centerX(), r.centerY() + t.textSize/3f, t)
    }

    private fun clearOverlayRects() {
        pauseContinueRect.setEmpty()
        pauseExitRect.setEmpty()
        gameOverRetryRect.setEmpty()
        gameOverExitRect.setEmpty()
        victoryExitRect.setEmpty()
    }
}
