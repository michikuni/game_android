// Quiver.kt
package com.example.game_android.game.entities

import android.util.Log
import com.example.game_android.game.world.GameState

/**
 * Quiver that ONLY reloads when the player picks up ammo items.
 * No passive/timed reloading.
 */
class PickupQuiver(
    private val maxAmmo: Int = 10,
    initialAmmo: Int = maxAmmo
) {
    private var available = initialAmmo.coerceIn(0, maxAmmo)

    /** Returns how many arrows are currently available. */
    fun ammo(): Int = available

    /** Max capacity for external UIs. */
    fun capacity(): Int = maxAmmo

    /** Can we fire right now? */
    fun canFire(): Boolean = available > 0

    /**
     * Try to consume one arrow for a shot.
     * Returns true if one arrow was consumed.
     */
    fun tryConsume(gameState: GameState): Boolean {
        if (gameState.paused) {
            Log.w("Quiver", "tryConsume: game paused, cannot consume")
            return false
        }
        if (available <= 0) {
            Log.d("Quiver", "tryConsume: no ammo")
            return false
        }
        available--
        Log.d("Quiver", "tryConsume: fired, $available/$maxAmmo left")
        return true
    }

    /**
     * Add ammo from a pickup. Will NOT exceed maxAmmo.
     * @param amount requested amount to add (<=0 is treated as 0)
     * @return how many arrows were actually added
     */
    fun addAmmo(amount: Int): Int {
        val room = maxAmmo - available
        if (room <= 0) {
            Log.d("Quiver", "addAmmo: already full ($available/$maxAmmo)")
            return 0
        }
        val add = amount.coerceAtLeast(0).coerceAtMost(room)
        available += add
        Log.d("Quiver", "addAmmo: +$add → $available/$maxAmmo")
        return add
    }

    /** Optional helper if you ever want to set an exact count safely. */
    fun setAmmoUnsafe(value: Int) {
        available = value.coerceIn(0, maxAmmo)
        Log.d("Quiver", "setAmmoUnsafe: $available/$maxAmmo")
    }
}
