package com.example.game_android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.game_android.game.GameView

class GameActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(GameView(this))
    }
}