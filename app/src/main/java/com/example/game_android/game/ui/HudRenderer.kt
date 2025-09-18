package com.example.game_android.game.ui

import android.graphics.*
import com.example.game_android.game.entities.Boss
import com.example.game_android.game.entities.Player
import com.example.game_android.game.core.InputController
import com.example.game_android.game.world.GameState

class HudRenderer(private val input: InputController) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG);
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f; typeface =
        Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun drawHud(c: Canvas, player: Player, boss: Boss, state: GameState) {
        val r = RectF(16f, 16f, 48f, 48f); repeat(3) { i ->
            p.color = if (i < player.hp) Color.RED else Color.DKGRAY; c.drawRoundRect(
            r.left + i * 40,
            r.top,
            r.right + i * 40,
            r.bottom,
            8f,
            8f,
            p
        )
        }; if (boss.alive) {
            val barW = c.width * 0.4f;
            val bar = RectF(c.width / 2f - barW / 2, 16f, c.width / 2f + barW / 2, 28f); p.color =
                Color.GRAY; c.drawRect(bar, p); p.color = Color.MAGENTA;
            val w = barW * (boss.hp / 30f); c.drawRect(
                RectF(
                    bar.left,
                    bar.top,
                    bar.left + w,
                    bar.bottom
                ), p
            )
        }; input.buttons().forEach { (rect, label) -> drawButton(c, rect, label) }
    }

    private fun drawButton(c: Canvas, r: RectF, label: String) {
        p.shader = LinearGradient(
            r.left,
            r.top,
            r.right,
            r.bottom,
            Color.argb(160, 60, 60, 70),
            Color.argb(160, 30, 30, 40),
            Shader.TileMode.CLAMP
        ); c.drawRoundRect(r, 20f, 20f, p); p.shader = null; t.textAlign =
            Paint.Align.CENTER; t.textSize = minOf(r.width(), r.height()) * 0.5f; c.drawText(
            label,
            r.centerX(),
            r.centerY() + t.textSize / 3f,
            t
        )
    }

    enum class Action { PauseToggle, Exit, Continue, Retry, BackToMenu }

    fun drawOverlays(c: Canvas, s: GameState, onAction: (Action) -> Unit) {
        if (!s.anyOverlay()) return; p.color = Color.argb(180, 0, 0, 0); c.drawRect(
            0f,
            0f,
            c.width.toFloat(),
            c.height.toFloat(),
            p
        ); t.textAlign = Paint.Align.CENTER; t.textSize = 64f
        if (s.paused) {
            c.drawText("PAUSED", c.width / 2f, c.height * 0.35f, t);
            val bw = c.width * 0.3f;
            val bh = c.height * 0.12f;
            val cont = RectF(
                c.width / 2f - bw / 2,
                c.height * 0.45f,
                c.width / 2f + bw / 2,
                c.height * 0.45f + bh
            );
            val exit = RectF(
                c.width / 2f - bw / 2,
                cont.bottom + 24f,
                c.width / 2f + bw / 2,
                cont.bottom + 24f + bh
            ); drawButton(c, cont, "Continue"); drawButton(c, exit, "Exit")
        }
        if (s.gameOver) {
            c.drawText("GAME OVER", c.width / 2f, c.height * 0.35f, t);
            val bw = c.width * 0.3f;
            val bh = c.height * 0.12f;
            val retry = RectF(
                c.width / 2f - bw / 2,
                c.height * 0.45f,
                c.width / 2f + bw / 2,
                c.height * 0.45f + bh
            );
            val exit = RectF(
                c.width / 2f - bw / 2,
                retry.bottom + 24f,
                c.width / 2f + bw / 2,
                retry.bottom + 24f + bh
            ); drawButton(c, retry, "Retry"); drawButton(c, exit, "Exit")
        }
        if (s.victory) {
            c.drawText("VICTORY!", c.width / 2f, c.height * 0.35f, t);
            val bw = c.width * 0.4f;
            val bh = c.height * 0.12f;
            val exit = RectF(
                c.width / 2f - bw / 2,
                c.height * 0.45f,
                c.width / 2f + bw / 2,
                c.height * 0.45f + bh
            ); drawButton(c, exit, "Back to Menu")
        }
    }

    fun handleTouch(
        x: Float,
        y: Float,
        state: GameState,
        onPauseToggle: () -> Unit,
        onExit: () -> Unit,
        onContinue: () -> Unit,
        onRetry: () -> Unit,
        onBackToMenu: () -> Unit
    ): Boolean {
        // Nếu đang hiển thị overlay nào → ưu tiên bắt nút của overlay
        if (state.gameOver) {
            if (gameOverRetryRect.contains(x, y)) { onRetry(); return true }
            if (gameOverExitRect.contains(x, y)) { onExit(); return true }
            return false
        }
        if (state.victory) {
            if (victoryExitRect.contains(x, y)) { onBackToMenu(); return true }
            return false
        }
        if (state.paused) {
            if (pauseContinueRect.contains(x, y)) { onContinue(); return true }
            if (pauseExitRect.contains(x, y)) { onExit(); return true }
            return false
        }

        // Không có overlay → thử nút Pause trên HUD
        if (pauseButtonRect.contains(x, y)) { onPauseToggle(); return true }

        return false
    }

}