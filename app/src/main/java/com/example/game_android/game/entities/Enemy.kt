package com.example.game_android.game.entities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.game_android.game.core.SpriteLoader


class Enemy(
    px: Float, py: Float, ctx: Context
) : PhysicsBody {
    override var x = px;
    override var y = py;
    override var vx = 0f;
    override var vy = 0f;
    override val w = 24f;
    override val h = 28f;
    override var canJump = false;
    var patrolRight = true;
    var alive = true;
    private var cd = 0;
    private val sp = SpriteLoader.loadSet(ctx, "enemy_", 6);
    private var f = 0;
    private var t = 0;
    fun draw(c: Canvas) {
        if (!alive) return;
        if (sp != null) {
            c.drawBitmap(sp[f % sp.size], null, RectF(x, y, x + w, y + h), null);
            if (++t % 8 == 0)
                f++
        } else {
            val p = Paint();
            p.color = Color.YELLOW;
            c.drawRect(x, y, x + w, y + h, p)
        }
    }

    fun tryShoot(out: MutableList<Projectile>, tx: Float, ty: Float) {
    }

    fun hit() {
        alive = false
    }
}