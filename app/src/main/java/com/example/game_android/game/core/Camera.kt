package com.example.game_android.game.core

class Camera {
    var x = 0f
    var y = 0f

    fun follow(px: Float, py: Float, vw: Int, vh: Int, ww: Float, wh: Float) {
        val maxX = (ww - vw).coerceAtLeast(0f)
        val maxY = (wh - vh).coerceAtLeast(0f)

        x = (px - vw / 2f).coerceIn(0f, maxX)
        y = (py - vh / 2f).coerceIn(0f, maxY)
    }
}
