package com.example.game_android.game.core

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import com.example.game_android.R
import com.example.game_android.game.world.GameState
import com.example.game_android.game.entities.Player


class InputController(private val view: View, context: Context) {
    // --- Button hit boxes ---
    private val btnLeft = RectF()
    private val btnRight = RectF()
    private val btnJump = RectF()
    private val btnFire = RectF()
    private val btnMelee = RectF()
    private val btnPause = RectF()

    // --- Active states exposed to GameView ---
    var left = false;     private set
    var right = false;    private set
    var jump = false;     private set
    var fire = false;     private set
    var melee = false; private set

    // Track which pointer currently owns each button (null if none)
    private var leftPid: Int? = null
    private var rightPid: Int? = null
    private var jumpPid: Int? = null
    private var firePid: Int? = null
    private var meleePid: Int? = null

    // Allow small drifts while “holding”
    private val touchSlopPx = view.resources.displayMetrics.density * 16f

    fun layout(w: Int, h: Int) {
        val pad = 30f
        val bw = w * 0.15f
        val bh = h * 0.3f

        // Left cluster (smaller)
        btnLeft.set(pad, h - bh - pad, pad + bw, h - pad)
        btnRight.set(btnLeft.right + pad, btnLeft.top, btnLeft.right + pad + bw, btnLeft.bottom)

        // Right cluster — smaller buttons; Fire above Jump; Melee left of Jump
        val smallW = bw * 0.70f // was 0.80f
        val smallH = bh * 0.70f // was 0.80f

        // Jump (bottom-right)
        btnJump.set(
            w - smallW - pad,
            h - smallH - pad,
            w - pad,
            h - pad
        )

        // Melee (left of Jump, same baseline)
        btnMelee.set(
            btnJump.left - pad - smallW,
            btnJump.top,
            btnJump.left - pad,
            btnJump.bottom
        )

        // Fire/Range (stacked above Jump)
        btnFire.set(
            btnJump.left,
            btnJump.top - pad - smallH,
            btnJump.right,
            btnJump.top - pad
        )

        // Pause unchanged
        val small = minOf(bw, bh) * 0.40f
        btnPause.set(w - pad - small, pad, w - pad, pad + small)
    }


    private fun RectF.containsWithSlop(x: Float, y: Float, slop: Float): Boolean {
        return x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
    }

    private fun acquireIfHit(pid: Int, x: Float, y: Float) {
        when {
            leftPid == null && btnLeft.containsWithSlop(x, y, touchSlopPx)   -> leftPid = pid
            rightPid == null && btnRight.containsWithSlop(x, y, touchSlopPx) -> rightPid = pid
            jumpPid == null && btnJump.containsWithSlop(x, y, touchSlopPx)   -> jumpPid = pid
            firePid == null && btnFire.containsWithSlop(x, y, touchSlopPx)   -> firePid = pid
            meleePid == null && btnMelee.containsWithSlop(x, y, touchSlopPx) -> meleePid = pid
        }
        recomputeStates()
    }

    private fun releaseIfOwner(pid: Int) {
        if (leftPid == pid) leftPid = null
        if (rightPid == pid) rightPid = null
        if (jumpPid == pid) jumpPid = null
        if (firePid == pid) firePid = null
        if (meleePid == pid) meleePid = null
        recomputeStates()
    }

    private fun recomputeStates() {
        left  = leftPid  != null
        right = rightPid != null
        jump  = jumpPid  != null
        fire  = firePid  != null
        melee = meleePid != null  // NEW
    }

    // Verify owners still make sense (pointer still down and within slop).
    private fun validateOwners(e: MotionEvent) {
        fun stillDown(pid: Int?, rect: RectF): Int? {
            if (pid == null) return null
            val idx = e.findPointerIndex(pid)
            if (idx == -1) return null // pointer left
            val x = e.getX(idx); val y = e.getY(idx)
            return if (rect.containsWithSlop(x, y, touchSlopPx)) pid else null
        }
        leftPid  = stillDown(leftPid,  btnLeft)
        rightPid = stillDown(rightPid, btnRight)
        jumpPid  = stillDown(jumpPid,  btnJump)
        firePid  = stillDown(firePid,  btnFire)
        meleePid = stillDown(meleePid, btnMelee)
        recomputeStates()
    }

    fun onTouchEvent(e: MotionEvent, s: GameState) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(e.actionIndex); val y = e.getY(e.actionIndex)
                val pid = e.getPointerId(e.actionIndex)

                // Pause has priority and toggles immediately
                if (btnPause.containsWithSlop(x, y, touchSlopPx)) {
                    s.paused = !s.paused
                    view.performClick()
                    return
                }

                // Try to acquire one button for this pointer
                acquireIfHit(pid, x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                // Existing owners hold as long as within slop
                validateOwners(e)

                // Also allow new acquisitions if a free finger moves onto a free button
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    val x = e.getX(i); val y = e.getY(i)

                    // If this pid already owns something, skip
                    if (pid == leftPid || pid == rightPid || pid == jumpPid || pid == firePid || pid == meleePid) continue

                    // Try to latch any free button it’s over
                    acquireIfHit(pid, x, y)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pid = e.getPointerId(e.actionIndex)
                releaseIfOwner(pid)
            }
        }
    }

    enum class BtnKind { Left, Right, Jump, Fire, Pause, Melee }
    data class ButtonSpec(val rect: RectF, val kind: BtnKind)

    fun buttons(): List<ButtonSpec> = listOf(
        ButtonSpec(btnLeft,  BtnKind.Left),
        ButtonSpec(btnRight, BtnKind.Right),
        ButtonSpec(btnJump,  BtnKind.Jump),
        ButtonSpec(btnFire,  BtnKind.Fire),
        ButtonSpec(btnMelee, BtnKind.Melee),
        ButtonSpec(btnPause, BtnKind.Pause),
    )

    fun jumpPressed(player: Player): Boolean {
        val pressed = jump && !player.wasJump
        player.wasJump = jump
        return pressed && player.canJump
    }

    fun isPauseHit(x: Float, y: Float): Boolean = btnPause.containsWithSlop(x, y, touchSlopPx)

}
