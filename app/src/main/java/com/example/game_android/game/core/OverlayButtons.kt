package com.example.game_android.game.core

import android.graphics.RectF
import com.example.game_android.game.world.GameState


class OverlayButtons {
    var retryRect = RectF()
    var exitRect = RectF()
    var continueRect = RectF()
    var victoryExitRect = RectF()


    fun handleClick(x: Float, y: Float, state: GameState, onExit: () -> Unit, onRetry: () -> Unit) {
        if (state.gameOver) {
            if (retryRect.contains(x, y)) onRetry()
            if (exitRect.contains(x, y)) onExit()
        }
        if (state.victory) {
            if (victoryExitRect.contains(x, y)) onExit()
        }
        if (state.paused) {
            if (continueRect.contains(x, y)) state.paused = false
            if (exitRect.contains(x, y)) onExit()
        }
    }
}