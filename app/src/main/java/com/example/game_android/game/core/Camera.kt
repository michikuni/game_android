package com.example.game_android.game.core

class Camera {
    var x = 0f
    var y = 0f

    fun follow(px: Float, py: Float, sw: Int, sh: Int, ww: Float, wh: Float) {
        // Trường hợp kích thước chưa sẵn sàng → tránh crash
        if (sw <= 0 || sh <= 0 || ww <= 0f || wh <= 0f) {
            x = 0f; y = 0f
            return
        }

        // Nếu map nhỏ hơn màn hình → max = 0 (cố định tại mép)
        val maxX = kotlin.math.max(0f, ww - sw)
        val maxY = kotlin.math.max(0f, wh - sh)

        val targetX = px - sw / 2f
        val targetY = py - sh / 2f

        x = targetX.coerceIn(0f, maxX)
        y = targetY.coerceIn(0f, maxY)
    }
}
