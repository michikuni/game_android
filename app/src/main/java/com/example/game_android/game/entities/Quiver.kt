// Quiver.kt
package com.example.game_android.game.entities

class Quiver(
    private val maxAmmo: Int = 5,
    private val cooldownTicks: Int = 90  // 1.5s at 60 FPS; tweak
) {
    private var available = maxAmmo
    private val reloading = ArrayDeque<Int>() // each entry = ticks remaining

    fun tick() {
        // decrement all timers; return ammo when ready
        val n = reloading.size
        repeat(n) {
            var t = reloading.removeFirst() - 1
            if (t <= 0) {
                available = (available + 1).coerceAtMost(maxAmmo)
            } else {
                reloading.addLast(t)
            }
        }
    }

    fun canFire(): Boolean = available > 0

    /** Consumes one arrow and starts its cooldown. Returns true if consumed. */
    fun tryConsume(): Boolean {
        if (available <= 0) return false
        available--
        reloading.addLast(cooldownTicks)
        return true
    }

    fun ammo(): Int = available
}
