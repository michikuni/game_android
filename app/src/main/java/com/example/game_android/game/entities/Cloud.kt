package com.example.game_android.game.entities

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

class Cloud(var x: Float, var y: Float) {
    private val w = 64f;
    private val h = 32f;
    fun update(cam: com.example.game_android.game.core.Camera, vw: Int) {
        x += 0.2f; if (x - cam.x > vw + 60) x -= 400f
    }

    fun draw(c: Canvas, cam: com.example.game_android.game.core.Camera) {
        val p = Paint(); p.color = Color.argb(180, 220, 220, 240); c.drawOval(
            RectF(
                x - cam.x,
                y - cam.y,
                x - cam.x + w,
                y - cam.y + h
            ), p
        )
    }
}
