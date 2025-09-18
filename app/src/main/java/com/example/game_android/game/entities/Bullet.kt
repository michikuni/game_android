package com.example.game_android.game.entities

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF


class Bullet(var x: Float, var y: Float, var vx: Float) {
    var dead = false;
    private val r = 4f;
    fun update(worldW: Float) {
        x += vx; if (x < 0 || x > worldW) dead = true
    }

    fun draw(c: Canvas) {
        val p = Paint(); p.color = Color.WHITE; c.drawCircle(x, y, r, p)
    }

    fun overlaps(b: PhysicsBody): Boolean {
        val a = RectF(x - r, y - r, x + r, y + r);
        val bb =
            b.bounds(); return a.left < bb.right && a.right > bb.left && a.top < bb.bottom && a.bottom > bb.top
    }
}