// Projectile.kt
package com.example.game_android.game.entities

import android.graphics.Canvas
import android.view.ViewDebug

interface Projectile {
    var x: Float
    var y: Float
    var w: Float
    var h: Float
    var dead: Boolean
    var showHitbox: Boolean

    fun update(worldW: Float)
    fun draw(c: Canvas)
    fun overlaps(b: PhysicsBody): Boolean
    fun bounds() = android.graphics.RectF(x, y, x + w, y + h)
}
