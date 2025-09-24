package com.example.game_android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.game_android.game.GameView
import com.example.game_android.game.core.SoundManager

class GameActivity : AppCompatActivity() {
    private lateinit var sound: SoundManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sound = SoundManager(applicationContext)
        setContentView(GameView(this))
    }

    override fun onResume() {
        super.onResume()
//        sound.resumeBgmIfEnabled()
    }

    override fun onPause() {
        // Nếu muốn tắt nhạc khi app background:
//        sound.pauseBgm()
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }
}