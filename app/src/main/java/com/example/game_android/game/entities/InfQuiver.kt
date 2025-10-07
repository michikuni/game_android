// Quiver.kt
package com.example.game_android.game.entities

import android.util.Log
import com.example.game_android.game.world.GameState

class InfQuiver(
    private val maxAmmo: Int = 5,
    private val cooldownTicks: Int = 30, // 0.5s at 60fps
) {
    private var available = maxAmmo
    private val reloading = ArrayDeque<Int>() // each entry = ticks remaining

    fun tick(gameState: GameState) {
        if (gameState.paused) return
        // decrement all timers; return ammo when ready
        val n = reloading.size
        repeat(n) {
            var t = reloading.removeFirst() - 1
            Log.d("Quiver", "tick: reloading arrow, $t ticks left")
            if (t <= 0) {
                available = (available + 1).coerceAtMost(maxAmmo)
            } else {
                reloading.addLast(t)
            }
        }
    }

    fun canFire(): Boolean = available > 0

    /** Consumes one arrow and starts its cooldown. Returns true if consumed. */
    fun tryConsume(gameState: GameState): Boolean {
        if (gameState.paused) {
            Log.w("Quiver", "tryConsume: game paused, cannot consume")
            return false
        }
        Log.d("Quiver", "tryConsume: $available arrows left")
        if (available <= 0) return false
        available--
        reloading.addLast(cooldownTicks)
        return true
    }

    fun ammo(): Int = available
}
