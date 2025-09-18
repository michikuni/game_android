package com.example.game_android.game.entities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.game_android.game.core.SpriteLoader

class Player(
    px:Float,
    py:Float,
    ctx: Context
): PhysicsBody{
    override var x=px;
    override var y=py;
    override var vx=0f;
    override var vy=0f;
    override val w=26f;
    override val h=30f;
    override var canJump=false;
    override var wasJump=false;
    var hp=3;
    private var cd=0;
    private val sp=SpriteLoader.loadSet(ctx,"player_",6);
    private var f=0;
    private var t=0;
    fun draw(c:Canvas){
        if(sp!=null){
            c.drawBitmap(sp[f%sp.size], null, RectF(x,y,x+w,y+h), null);
            if(++t%6==0)
                f++
        } else {
            val p=Paint();
            p.color=Color.CYAN;
            c.drawRect(x,y,x+w,y+h,p)
        }
    }
    fun tryShoot(out:MutableList<Bullet>){
        if(cd>0){
            cd--;return
        };
        val dir= if(vx>=0) 1 else -1;
        out.add(Bullet(x+w/2,y+h/2,7f*dir));
        cd=12 }
    fun hit(){
        if(hp>0)
            hp--
    }
    fun reset(px:Float,py:Float){
        x=px;
        y=py;
        vx=0f;
        vy=0f;
        hp=3;
        canJump=false;
        wasJump=false
    }
}