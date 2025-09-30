package com.example.game_android.game.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.example.game_android.R
import com.example.game_android.game.entities.Boss
import com.example.game_android.game.entities.Player
import com.example.game_android.game.core.InputController
import com.example.game_android.game.core.InputController.BtnKind
import com.example.game_android.game.world.GameState
import androidx.core.graphics.withRotation

class HudRenderer(private val input: InputController, private val context: Context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
        BitmapFactory.decodeResource(context.resources, R.drawable.heart) // heart.png -> R.drawable.heart
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
        colorFilter = PorterDuffColorFilter(Color.DKGRAY, PorterDuff.Mode.SRC_IN)
    }

    // --- Hearts (bitmap sprite with transparent trim) ---
    val heartSize = dp(32f)         // HUD size for each heart (scaled from 64px source)
    val heartGap  = dp(8f)          // spacing between hearts
    val heartsTop = dp(16f)
    val heartsLeftStart = dp(16f)

    // Arrow sprite caches (trim like Arrow.kt)
    private val arrowBmp: Bitmap by lazy {
        BitmapFactory.decodeResource(context.resources, R.drawable.arrow01_100x100)
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
        colorFilter = PorterDuffColorFilter(Color.DKGRAY, PorterDuff.Mode.SRC_IN)
    }

    fun drawHud(
        c: Canvas,
        player: Player,
        boss: Boss,
        state: GameState,
        isMuted: Boolean,
        isBgMuted: Boolean,
        ammoCapacity: Int,
        ammoCount: Int
    ) {
        // --- Hearts ---
        repeat(3) { i ->
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

        val muteIconRes = if (isBgMuted) R.drawable.volume_off_24px else R.drawable.volume_up_24px
        val muteDr = dr(muteIconRes)
        drawSquareButton(c, topMuteRect, muteDr)   // nền xám nhạt + icon

        // --- Boss HP ---
        if (boss.alive) {
            val barW = c.width * 0.4f
            val bar = RectF(c.width / 2f - barW / 2, 16f, c.width / 2f + barW / 2, 28f)
            p.color = Color.GRAY
            c.drawRect(bar, p)
            p.color = Color.MAGENTA
            val w = barW * (boss.hp / 30f)
            c.drawRect(RectF(bar.left, bar.top, bar.left + w, bar.bottom), p)
        }

        // --- HUD buttons ---
        input.buttons().forEach { spec ->
            val resId = when (spec.kind) {
                BtnKind.Left  -> R.drawable.arrow_circle_left_24px
                BtnKind.Right -> R.drawable.arrow_circle_right_24px
                BtnKind.Jump  -> R.drawable.jump_24px
                BtnKind.Fire  -> R.drawable.fire_24px
                BtnKind.Pause -> R.drawable.pause_24px
                BtnKind.Mute  -> if (isMuted) R.drawable.volume_off_24px else R.drawable.volume_up_24px
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
        kind == BtnKind.Left || kind == BtnKind.Right || kind == BtnKind.Jump || kind == BtnKind.Fire

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

    fun drawOverlays(c: Canvas, s: GameState, onAction: (Action) -> Unit) {
        clearOverlayRects()

        if (!s.anyOverlay()) return

        p.color = Color.argb(180, 0, 0, 0)
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), p)

        t.textAlign = Paint.Align.CENTER
        t.textSize = 64f

        if (s.paused) {
            c.drawText("PAUSED", c.width/2f, c.height*0.35f, t)
            val bw = c.width*0.3f; val bh = c.height*0.12f
            pauseContinueRect.set(
                c.width/2f - bw/2, c.height*0.45f,
                c.width/2f + bw/2, c.height*0.45f + bh
            )
            pauseExitRect.set(
                c.width/2f - bw/2, pauseContinueRect.bottom + 24f,
                c.width/2f + bw/2, pauseContinueRect.bottom + 24f + bh
            )
            drawButtonFromText(c, pauseContinueRect, "Continue")
            drawButtonFromText(c, pauseExitRect, "Exit")
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
        }

        if (s.victory) {
            c.drawText("VICTORY!", c.width/2f, c.height*0.35f, t)
            val bw = c.width*0.4f; val bh = c.height*0.12f
            victoryExitRect.set(
                c.width/2f - bw/2, c.height*0.45f,
                c.width/2f + bw/2, c.height*0.45f + bh
            )
            drawButtonFromText(c, victoryExitRect, "Back to Menu")
        }
    }

    fun handleTouch(
        x: Float,
        y: Float,
        state: GameState,
        onPauseToggle: () -> Unit,
        onMuteToggle: () -> Unit,
        onBgMuteToggle: () -> Unit,
        onExit: () -> Unit,
        onContinue: () -> Unit,
        onRetry: () -> Unit,
        onBackToMenu: () -> Unit
    ): Boolean {
        if (state.gameOver) {
            if (gameOverRetryRect.contains(x,y)) { onRetry(); return true }
            if (gameOverExitRect.contains(x,y)) { onExit(); return true }
            return false
        }
        if (state.victory) {
            if (victoryExitRect.contains(x,y)) { onBackToMenu(); return true }
            return false
        }
        if (state.paused) {
            if (pauseContinueRect.contains(x,y)) { onContinue(); return true }
            if (pauseExitRect.contains(x,y)) { onExit(); return true }
            return false
        }

        if (input.isPauseHit(x,y)) { onPauseToggle(); return true }
        if (input.isMuteHit(x,y)) { onMuteToggle(); return true }
        if (input.isBgMuteHit(x,y)) { onBgMuteToggle(); return true }
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
