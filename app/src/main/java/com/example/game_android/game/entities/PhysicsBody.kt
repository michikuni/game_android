package com.example.game_android.game.entities

import android.graphics.*

interface PhysicsBody {
    var x: Float;
    var y: Float;
    var vx: Float;
    var vy: Float;
    val w: Float;
    val h: Float;
    var canJump: Boolean;
    var wasJump: Boolean
        get() = false;
        set(value) {};

    fun bounds() = RectF(x, y, x + w, y + h)
}
