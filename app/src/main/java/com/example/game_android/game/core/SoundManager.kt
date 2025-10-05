package com.example.game_android.game.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import androidx.annotation.RawRes
import com.example.game_android.R
import kotlin.math.pow

class SoundManager(val ctx: Context) {

    // ------------------------------
    // Generic volume control (UI -> perceptual dB curve -> linear * master)
    // ------------------------------
    private data class VolumeControl(
        var ui: Float = 1f,           // 0..1 from slider
        var master: Float = 0.5f,     // global cap (0..1). 0.5 = -6 dB-ish
        var minDb: Float = -50f       // bottom in dB (so low-end gets really quiet)
    ) {
        private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
        private fun uiToPerceptualLinear(u: Float): Float {
            val clamped = u.coerceIn(0f, 1f)
            val db = minDb * (1f - clamped)          // u=0 → minDb, u=1 → 0 dB
            return dbToLinear(db)
        }

        fun applied(): Float = (uiToPerceptualLinear(ui) * master).coerceIn(0f, 1f)
    }

    // Expose UI values (for HUD sliders)
    val bgmVolumeUi: Float get() = bgmVol.ui
    val sfxVolumeUi: Float get() = sfxVol.ui

    private val bgmVol = VolumeControl(ui = 1f, master = 0.5f, minDb = -50f)
    private val sfxVol = VolumeControl(ui = 1f, master = 0.6f, minDb = -45f)

    // ------------------------------
    // Music (BGM)
    // ------------------------------
    private var bgm: MediaPlayer? =
        MediaPlayer.create(ctx.applicationContext, R.raw.hk_ss_the_mist).apply {
            isLooping = true
            val v = bgmVol.applied()
            setVolume(v, v)
        }

    private fun applyBgmVolume() {
        val v = bgmVol.applied()
        bgm?.setVolume(v, v)
        bgm?.let { mp ->
            if (v <= 0.0005f && mp.isPlaying) mp.pause()
            if (v > 0.0005f && !mp.isPlaying) mp.start()
        }
    }

    fun setBgmVolume(ui: Float) {
        bgmVol.ui = ui.coerceIn(0f, 1f); applyBgmVolume()
    }

    fun setBgmMasterAttenuation(multiplier: Float) {
        bgmVol.master = multiplier.coerceIn(0f, 1f); applyBgmVolume()
    }

    fun setBgmMinDb(minDb: Float) {
        bgmVol.minDb = minDb; applyBgmVolume()
    }

    fun startBgmIfNeeded() {
        if (bgmVol.applied() > 0.0005f && bgm?.isPlaying == false) bgm?.start()
    }

    fun pauseBgm() {
        bgm?.pause()
    }

    fun stopBgm() {
        try {
            bgm?.stop()
        } catch (_: Exception) {
        }
        try {
            bgm?.reset()
        } catch (_: Exception) {
        }
        bgm = MediaPlayer.create(ctx.applicationContext, R.raw.hk_ss_the_mist)?.apply {
            isLooping = true
            val v = bgmVol.applied()
            setVolume(v, v)
        }
    }

    // ------------------------------
    // SFX
    // ------------------------------
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    enum class Sfx {
        ArrowHitEnemy, PlayerDie, PlayerWalk, PlayerHurt, PlayerShoot,
        ArrowHitWall, FireballExplode, FireballShoot, WitchHurt, WitchDie,
        DaggerSwing, GoblinDie, SkeletonDie, SwordSwing, SkeletonHurt, GoblinHurt
    }

    private val sfxIds = hashMapOf<Sfx, Int>()

    init {
        register(ctx, Sfx.ArrowHitEnemy, R.raw.arrow_hit_enemy)
        register(ctx, Sfx.PlayerHurt, R.raw.terraria_male_player_hurt_sound)
        register(ctx, Sfx.PlayerShoot, R.raw.bow_load_and_release)
        register(ctx, Sfx.ArrowHitWall, R.raw.arrow_hit_wall)
        register(ctx, Sfx.PlayerWalk, R.raw.walk4)
        register(ctx, Sfx.FireballExplode, R.raw.fireball_explode)
        register(ctx, Sfx.FireballShoot, R.raw.fireball_shoot)
        register(ctx, Sfx.WitchHurt, R.raw.witch_hurt)
        register(ctx, Sfx.WitchDie, R.raw.witch_death_max)
        register(ctx, Sfx.DaggerSwing, R.raw.dagger_swing)
        register(ctx, Sfx.GoblinDie, R.raw.goblin_death)
        register(ctx, Sfx.GoblinHurt, R.raw.goblin_hurt)
        register(ctx, Sfx.SkeletonDie, R.raw.skeleton_death)
        register(ctx, Sfx.SwordSwing, R.raw.sword_swing)
        register(ctx, Sfx.SkeletonHurt, R.raw.skeleton_hurt)
    }

    private fun register(context: Context, kind: Sfx, @RawRes rawId: Int) {
        sfxIds[kind] = soundPool.load(context, rawId, 1)
    }

    // SFX controls
    fun setSfxVolume(ui: Float) {
        sfxVol.ui = ui.coerceIn(0f, 1f)
    }

    fun setSfxMasterAttenuation(multiplier: Float) {
        sfxVol.master = multiplier.coerceIn(0f, 1f)
    }

    fun setSfxMinDb(minDb: Float) {
        sfxVol.minDb = minDb
    }

    /** volume: additional per-call multiplier (0..1); rate: 0.5..2.0 */
    fun play(kind: Sfx, volume: Float = 1f, rate: Float = 1f) {
        val id = sfxIds[kind] ?: return
        val base = sfxVol.applied()             // perceptual * master (0..1)
        val v = (base * volume).coerceIn(0f, 1f)
        if (v <= 0f) return
        soundPool.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2.0f))
    }

    fun playArrowHitWall() = play(Sfx.ArrowHitWall, volume = 0.1f, rate = 1f)
    fun playArrowHitEnemy() = play(Sfx.ArrowHitEnemy, volume = 0.1f, rate = 1f)
    fun playFireballExplode() = play(Sfx.FireballExplode, volume = 0.5f, rate = 1f)
    fun playFireballShoot() = play(Sfx.FireballShoot, volume = 0.5f, rate = 1f)
    fun playWitchDie() = play(Sfx.WitchDie, volume = 0.15f, rate = 1f)
    fun playWitchHurt() = play(Sfx.WitchHurt, volume = 0.3f, rate = 1f)
    fun playDaggerSwing() = play(Sfx.DaggerSwing, volume = 1f, rate = 1f)
    fun playSwordSwing() = play(Sfx.SwordSwing, volume = 1f, rate = 1f)
    fun playSkeletonDie() = play(Sfx.SkeletonDie, volume = 1f, rate = 1f)
    fun playGoblinDie() = play(Sfx.GoblinDie, volume = 1f, rate = 1f)

    // ------------------------------
    // Lifecycle
    // ------------------------------
    fun release() {
        try {
            bgm?.release()
        } catch (_: Exception) {
        }
        bgm = null
        soundPool.release()
    }

    // --- Looping SFX control -----------------------------

    /** Start an infinite loop for the given SFX. Returns SoundPool streamId (0 if failed). */
    fun playLoop(kind: Sfx, volume: Float = 1f, rate: Float = 1f): Int {
        val id = sfxIds[kind] ?: return 0
        val base = sfxVol.applied()
        val v = (base * volume).coerceIn(0f, 1f)
        if (v <= 0f) return 0
        val r = rate.coerceIn(0.5f, 2.0f)
        // loop = -1 for infinite
        return soundPool.play(id, v, v, /*priority*/1, /*loop*/-1, r)
    }

    /** Change volume of an existing loop (streamId from playLoop). */
    fun setLoopVolume(streamId: Int, volume: Float) {
        if (streamId == 0) return
        val v = (sfxVol.applied() * volume).coerceIn(0f, 1f)
        soundPool.setVolume(streamId, v, v)
    }

    /** Change playback rate of an existing loop (0.5..2.0). */
    fun setLoopRate(streamId: Int, rate: Float) {
        if (streamId == 0) return
        soundPool.setRate(streamId, rate.coerceIn(0.5f, 2.0f))
    }

    /** Stop a running loop. */
    fun stopLoop(streamId: Int) {
        if (streamId == 0) return
        soundPool.stop(streamId)
    }
}
