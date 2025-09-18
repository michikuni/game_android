package com.example.game_android.game.core

import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.example.game_android.game.world.GameState
import com.example.game_android.game.entities.Player


class InputController(private val view: View) {
    private val btnLeft = RectF()
    private val btnRight = RectF()
    private val btnJump = RectF()
    private val btnFire = RectF()
    private val btnPause = RectF()



    var left = false; private set
    var right = false; private set
    var jump = false; private set
    var fire = false; private set


    fun layout(w: Int, h: Int) {
        val pad = 16f; val bw = w * 0.12f; val bh = h * 0.18f
        btnLeft.set(pad, h - bh - pad, pad + bw, h - pad)
        btnRight.set(btnLeft.right + pad, btnLeft.top, btnLeft.right + pad + bw, btnLeft.bottom)
        btnJump.set(w - bw - pad, h - bh - pad, w - pad, h - pad)
        btnFire.set(btnJump.left - pad - bw, btnJump.top, btnJump.left - pad, btnJump.bottom)
        btnPause.set(w - pad - bw * 0.7f, pad, w - pad, pad + bh * 0.5f)
    }


    private fun recalc(ev: MotionEvent) {
        left = false; right = false; jump = false; fire = false
        for (i in 0 until ev.pointerCount) {
            val x = ev.getX(i); val y = ev.getY(i)
            if (btnLeft.contains(x, y)) left = true
            if (btnRight.contains(x, y)) right = true
            if (btnJump.contains(x, y)) jump = true
            if (btnFire.contains(x, y)) fire = true
        }
    }


    fun onTouchEvent(e: MotionEvent, s: GameState) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
                if (btnPause.contains(x, y)) {
                    s.paused = !s.paused; view.performClick(); return
                }
                recalc(e)
            }
            MotionEvent.ACTION_MOVE -> recalc(e)
            else -> recalc(e)
        }
    }


    fun buttons(): List<Pair<RectF, String>> = listOf(
        btnLeft to "←", btnRight to "→", btnJump to "A", btnFire to "B", btnPause to "II"
    )


    fun jumpPressed(player: Player): Boolean {
        val pressed = jump && !player.wasJump
        player.wasJump = jump
        return pressed && player.canJump
    }
}