package com.example.game_android.game.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.example.game_android.R

class SoundManager(
    ctx: Context
) {
    private val soundPool: SoundPool
    private val idHitEnemy: Int
    private val idPlayerDie: Int
    private var bgm: MediaPlayer? = null

    var isMuted: Boolean = false
        private set

    var isBgMuted: Boolean = false
        private set

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
        idPlayerDie = soundPool.load(ctx, R.raw.terraria_male_player_hurt_sound, 1)
        bgm = MediaPlayer.create(ctx.applicationContext, R.raw.hidden_hit__grave).apply {
            isLooping = true   // <-- tự lặp khi chạy hết
            setVolume(1f, 1f)
        }
    }

    fun toggleBgMute() = setBgMuted(!isBgMuted)

    fun setBgMuted(mute: Boolean){
        isBgMuted = mute
        if (mute){
            bgm?.pause()
        } else{
            bgm?.start()
        }
    }

    fun toggleMute() = setMuted(!isMuted)

    fun setMuted(mute: Boolean) {
        isMuted = mute
        if (mute){
            bgm?.pause()
        } else{
            bgm?.start()
        }
    }

    fun startBgmLoop() {
        // Chỉ start nếu chưa chạy
        val mp = bgm ?: return
        if (!mp.isPlaying && !isMuted) {
            mp.seekTo(0)
            mp.start()
        } else if (mp.isPlaying && isMuted) {
            mp.stop()
        }
    }
    fun pauseBgm() { bgm?.pause() }

    fun resumeBgmIfEnabled() {
        val mp = bgm ?: return
        if (!mp.isPlaying && !isMuted) mp.start()
    }

    fun stopBgm() {
        bgm?.stop()
        // cần chuẩn bị lại nếu muốn phát lại sau khi stop()
        bgm?.reset()
        // Tạo lại từ đầu (tuỳ bạn) — ví dụ:
        // bgm = MediaPlayer.create(appCtx, R.raw.bgm_main_menu_or_level).apply { isLooping = true }
    }
    fun playHitEnemy(volume: Float = 1f){
        if (!isMuted) {
            soundPool.play(idHitEnemy, volume, volume, 1, 0, 1f)
        }
    }

    fun playPlayerDie(volume: Float = 1f) {
        if (!isMuted) {
            soundPool.play(idPlayerDie, volume, volume, 1, 0, 1f)
        }
    }

    fun release() {
        try { bgm?.release() } catch (_: Exception) {}
        bgm = null
        soundPool.release()
    }
}