package com.example.game_android.game.world

class GameState {
    var paused = false;
    var gameOver = false;
    var victory = false;
    var mute = false
    fun anyOverlay() = paused || gameOver || victory;
    fun reset() {
        paused = false; gameOver = false; victory = false
    }
}
