package com.example.game_android.game.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

object DebugDrawUtils {
    fun drawPhysicsBounds(c: Canvas, bounds: RectF, paint: Paint) {
        c.drawRect(bounds, paint)
    }

    fun drawVisualBounds(c: Canvas, bounds: RectF, paint: Paint) {
        c.drawRect(bounds, paint)
    }

    fun drawFeetAnchor(c: Canvas, x: Float, y: Float, paint: Paint) {
        c.drawCircle(x, y, 3f, paint)
    }

    fun drawPhysicsBounds(c: Canvas, bounds: RectF) {
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(220, 0, 255, 0) // green = physics (collision) box
        }

        c.drawRect(bounds, paint)
    }

    fun drawVisualBounds(c: Canvas, bounds: RectF) {
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(220, 0, 180, 255) // cyan = visual (sprite) rect
        }
        c.drawRect(bounds, paint)
    }

    fun drawFeetAnchor(c: Canvas, x: Float, y: Float) {
        val paint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(220, 255, 180, 0) // orange = feet anchor
        }
        c.drawCircle(x, y, 3f, paint)
    }
}