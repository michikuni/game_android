package com.example.game_android.game.entities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.game_android.game.core.SpriteLoader

class Boss(
    px: Float, py: Float, ctx: Context
) : PhysicsBody {
    override var x = px;
    override var y = py;
    override var vx = 0f;
    override var vy = 0f;
    override val w = 48f;
    override val h = 54f;
    override var canJump = false;
    var hp = 30;
    val alive get() = hp > 0;
    private var cd = 0;
    private val sp = SpriteLoader.loadSet(ctx, "boss_", 6);
    private var f = 0;
    private var t = 0;
    fun draw(c: Canvas) {
        if (!alive) return;
        if (sp != null) {
            c.drawBitmap(sp[f % sp.size], null, RectF(x, y, x + w, y + h), null);
            if (++t % 6 == 0)
                f++
        } else {
            val p = Paint();
            p.color = Color.MAGENTA;
            c.drawRect(x, y, x + w, y + h, p)
        }
    }

    fun tryShoot(out: MutableList<Bullet>, tx: Float, ty: Float) {
        if (!alive) return;
        if (cd > 0) {
            cd--;return
        };
        val dir = if (tx < x) -1 else 1;
        out.add(Bullet(x + w / 2, y + h * 0.6f, 6f * dir)); cd = 30
    }

    fun hit() {
        hp--
    }
}
