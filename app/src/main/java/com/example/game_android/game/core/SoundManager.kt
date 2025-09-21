package com.example.game_android.game.core

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.example.game_android.R

class SoundManager(
    ctx: Context
) {
    private val soundPool: SoundPool
    private val idHitEnemy: Int
    private val idPlayerDie: Int

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attrs)
            .build()

        idHitEnemy = soundPool.load(ctx, R.raw.bomb, 1)
        idPlayerDie = soundPool.load(ctx, R.raw.bomb, 1)
    }

    fun playHitEnemy(volume: Float = 1f){
        soundPool.play(idHitEnemy, volume, volume,1, 0 ,1f)
    }

    fun playPlayerDie(volume: Float = 1f){
        soundPool.play(idPlayerDie, volume, volume, 1, 0, 1f)
    }

    fun release(){
        soundPool.release()
    }
}