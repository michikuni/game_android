package com.example.game_android.game

import android.content.Context
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.edit
import androidx.core.graphics.withTranslation
import com.example.game_android.R
import com.example.game_android.game.core.Camera
import com.example.game_android.game.core.Constants
import com.example.game_android.game.core.InputController
import com.example.game_android.game.core.SoundManager
import com.example.game_android.game.entities.ArmShard
import com.example.game_android.game.entities.Boss
import com.example.game_android.game.entities.Fireball
import com.example.game_android.game.entities.Goblin
import com.example.game_android.game.entities.LaserBolt
import com.example.game_android.game.entities.Player
import com.example.game_android.game.entities.Projectile
import com.example.game_android.game.entities.Skeleton
import com.example.game_android.game.entities.Witch
import com.example.game_android.game.ui.HudRenderer
import com.example.game_android.game.world.GameState
import com.example.game_android.game.world.TileMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var thread: Thread? = null

    @Volatile
    private var running = false

    // Core
    private val camera = Camera()
    private val input = InputController(this, context)
    private val state = GameState()

    // World & Entities
    private val map = TileMap(context)
    private val player = Player(map.playerStartX.toFloat(), map.playerStartY.toFloat(), context)
    private var boss = Boss(map.bossStartX.toFloat(), map.bossStartY.toFloat(), context)
    private var bossCorpseSettled = false
    private val witches = mutableListOf<Witch>()
    private val skeletons = mutableListOf<Skeleton>()
    private val goblins = mutableListOf<Goblin>()
    private val arrows = mutableListOf<Projectile>()
    private val enemyBullets = mutableListOf<Projectile>()

    private val sound = SoundManager(context)

    // Rendering helpers
    private val hud = HudRenderer(input, context)

    private var footstepStreamId: Int = 0
    private fun stopFootstepsIfAny() {
        if (footstepStreamId != 0) {
            sound.stopLoop(footstepStreamId)
            footstepStreamId = 0
        }
    }

    private var playerIsDying = false
    private var playerDeathAnimDone = false
    private var playerDeathSfxDone = false

    private enum class AwakeningPhase { NONE, ROCK, ROAR }

    private var awakeningPhase = AwakeningPhase.NONE
    private var rockDone = false
    private var appearDone = false
    private var roarDone = false

    // --- Scoring ---
    private val prefs = context.getSharedPreferences("scores", Context.MODE_PRIVATE)
    private var score = 0
    private var highScore = prefs.getInt("highScore", 0)

    private fun updateHighScoreIfNeeded() {
        if (score > highScore) {
            highScore = score
            prefs.edit { putInt("highScore", highScore) }
        }
    }

    // --- Boss death → victory gating
    private var bossDeathAnimDone = false
    private var bossDeathSfxDone = false

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        initWitches()
        initSkeletons()
        initGoblins()
        initPlayer()
        initBoss()
    }

    // --- Surface callbacks ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this).apply { start() }
        sound.setBgmMasterAttenuation(0.1f)
        sound.setSfxMasterAttenuation(0.5f)
        sound.startBgmIfNeeded()
    }


    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false; try {
            thread?.join()
        } catch (_: InterruptedException) {
        }
        sound.release()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        map.initBackground(width, height)
        input.layout(width, height)
    }

    // --- Game loop (fixed-step 60 FPS) ---
    override fun run() {
        var last = System.nanoTime()
        val step = 1_000_000_000.0 / 60.0
        var acc = 0.0
        while (running) {
            val now = System.nanoTime(); acc += (now - last); last = now
            var updated = false
            while (acc >= step) {
                update(); acc -= step; updated = true
            }
            if (updated) drawFrame()
        }
    }

    // --- Update world ---
    private fun update() {
        if (state.anyOverlay()) return

        // ────────────────── Player death flow: freeze world until both anim & sfx end ──────────────────
        if (playerIsDying) {
            if (playerDeathAnimDone && playerDeathSfxDone) {
                playerIsDying = false
                resetGame()
            }
            return
        }

        if (state.victory) {
            stopFootstepsIfAny()
            return
        }

        // ────────────────── Boss awakening cutscene (ROCK → ROAR) ──────────────────
        if (awakeningPhase != AwakeningPhase.NONE) {
            // Tick boss only; inner freeze flag prevents AI during ROAR
            boss.updateAiAndAnim(
                player.x + player.w * 0.5f, player.y + player.h * 0.5f,
                onGround = false,
                tileSolidAtPx = { false },
                enemyProjectiles = enemyBullets,
                playerBounds = player.bounds(),
                onDamagePlayer = { /* disabled in cutscene */ }
            )

            stopFootstepsIfAny()

            // Keep player frozen (but let their IDLE anim run in draw())
            player.vx = 0f; player.vy = 0f

            when (awakeningPhase) {
                AwakeningPhase.ROCK -> {
                    // Wait for BOTH: rock cinematic finished AND appearance finished
                    if (rockDone && appearDone) {
                        awakeningPhase = AwakeningPhase.ROAR
                        roarDone = false

                        // Freeze boss AI/movement/attacks during ROAR, show IDLE pose
                        boss.setCutsceneFrozen(true, poseIdle = true)

                        // Switch BGM and play roar
                        sound.switchBgm(R.raw.hk_ss_phantom, autoplay = true)
                        sound.playCinematic(SoundManager.Sfx.BossRoar, volume = 0.1f) {
                            roarDone = true
                        }
                    }
                }

                AwakeningPhase.ROAR -> {
                    if (roarDone) {
                        boss.setCutsceneFrozen(false)
                        awakeningPhase = AwakeningPhase.NONE
                    }
                }

                else -> Unit
            }
            return
        }

        // ────────────────── INPUT → movement (no input while attacking) ──────────────────
        val accel = 0.8f
        when {
            input.left && !input.right && !player.isAttacking ->
                player.vx = max(player.vx - accel, -Constants.PLAYER_MAX_SPD)

            input.right && !input.left && !player.isAttacking ->
                player.vx = min(player.vx + accel, Constants.PLAYER_MAX_SPD)

            else -> player.vx *= 0.8f
        }

        if (input.jumpPressed(player) && player.canJump && !player.isAttacking) {
            player.vy = -Constants.JUMP_VELOCITY
            player.canJump = false
            player.onJumpImpulse()
        }

        // Footsteps loop (grounded + moving + not attacking)
        run {
            val speed = abs(player.vx)
            val isWalking = player.canJump && speed > 0.2f && !player.isAttacking
            if (isWalking) {
                if (footstepStreamId == 0) {
                    val baseVol =
                        0.35f + 0.45f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
                    val rate = 0.9f + 0.3f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
                    footstepStreamId =
                        sound.playLoop(SoundManager.Sfx.PlayerWalk, volume = baseVol, rate = rate)
                } else {
                    val baseVol =
                        0.35f + 0.45f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
                    val rate = 0.9f + 0.3f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
                    sound.setLoopVolume(footstepStreamId, baseVol)
                    sound.setLoopRate(footstepStreamId, rate)
                }
            } else {
                stopFootstepsIfAny()
            }
        }

        // ────────────────── Player physics ──────────────────
        player.vy += Constants.GRAVITY
        map.moveAndCollide(player)

        // Shooting / Melee input
        if (input.fire && !player.isAttacking && player.ammo > 0) player.tryShoot(arrows)
        player.setMeleeHeld(input.melee)
        if (input.melee && !player.isAttacking) player.tryMelee()

        // ────────────────── Summon trigger → start cutscene ──────────────────
        if (awakeningPhase == AwakeningPhase.NONE && !boss.summoned) {
            val pcx = player.x + player.w * 0.5f
            val pcy = player.y + player.h * 0.5f
            val bcx = boss.x + boss.w * 0.5f
            val bcy = boss.y + boss.h * 0.55f
            val dx = pcx - bcx
            val dy = pcy - bcy
            if (kotlin.math.sqrt(dx * dx + dy * dy) <= boss.summonRadius()) {
                boss.triggerSummon()

                // clear any projectiles and enemies on screen
                arrows.clear()
                enemyBullets.clear()
                witches.clear()
                skeletons.clear()
                goblins.clear()

                // --- CUTSCENE START ---
                awakeningPhase = AwakeningPhase.ROCK
                rockDone = false; appearDone = false; roarDone = false

                stopFootstepsIfAny()
                sound.pauseBgm()
                sound.playCinematic(SoundManager.Sfx.RockCinematic, volume = 1f) { rockDone = true }

                // Cancel attacks & put player in a neutral pose; snap to ground
                player.forceIdleForCutscene(clearHurtBlink = true)
                settlePlayerToGroundOnce()
            }
        }

        // ────────────────── Boss update: dormant/appearing, active, or dying ──────────────────
        if (!boss.summoned) {
            boss.updateAiAndAnim(
                player.x + player.w * 0.5f, player.y + player.h * 0.5f,
                onGround = false,
                tileSolidAtPx = { false },
                enemyProjectiles = enemyBullets,
                playerBounds = player.bounds(),
                onDamagePlayer = { /* cannot happen while dormant/appearing */ }
            )
        } else if (boss.alive) {
            boss.vy += Constants.GRAVITY
            val onGroundPrev = boss.canJump
            boss.updateAiAndAnim(
                player.x + player.w * 0.5f, player.y + player.h * 0.5f,
                onGroundPrev,
                tileSolidAtPx = { r -> map.isSolidAtPxRect(r) },
                enemyProjectiles = enemyBullets,
                playerBounds = player.bounds(),
                onDamagePlayer = {
                    player.hit()
                    sound.play(SoundManager.Sfx.PlayerHurt)
                }
            )
            map.moveAndCollide(boss)
            bossCorpseSettled = false
        } else {
            if (boss.isDeadAndGone()) bossDeathAnimDone = true

            if (!state.victory && bossDeathAnimDone && bossDeathSfxDone) {
                stopFootstepsIfAny()
                updateHighScoreIfNeeded()
                state.victory = true

                // Pause BGM while the overlay is up and play a victory fanfare once
                sound.pauseBgm()
                sound.playCinematic(SoundManager.Sfx.Victory, volume = 0.5f) { /* no-op */ }
            }

            if (!bossCorpseSettled) {
                settleBossToGroundOnceForCorpse()
                bossCorpseSettled = true
            }
            boss.updateAiAndAnim(
                player.x + player.w * 0.5f, player.y + player.h * 0.5f,
                onGround = true,
                tileSolidAtPx = { false },
                enemyProjectiles = enemyBullets,
                playerBounds = player.bounds(),
                onDamagePlayer = { /* N/A */ }
            )
        }

        // ────────────────── Enemies update (AI + movement) ──────────────────
        witches.forEach { w ->
            if (!w.alive) return@forEach
            w.vy += Constants.GRAVITY
            val onGroundPrev = w.canJump
            w.updateAiAndAnim(player.x, player.y, onGroundPrev)
            map.moveAndCollide(w)
            w.updateAiAndAnim(player.x, player.y, w.canJump)
            w.tryShootFireball(
                enemyBullets,
                player.x + player.w * 0.5f,
                player.y + player.h * 0.4f
            )
        }

        skeletons.forEach { s ->
            if (!s.alive) return@forEach
            s.vy += Constants.GRAVITY
            val onGroundPrev = s.canJump
            s.updateAiAndAnim(player.x, player.y, onGroundPrev)
            map.moveAndCollide(s)
            s.updateAiAndAnim(player.x, player.y, s.canJump)
            val pBounds = player.bounds()
            if (s.canStartAttackOn(pBounds)) {
                s.tryAttack(player.x + player.w * 0.5f, player.y + player.h * 0.4f)
            }
            s.checkMeleeHit(player, sound, state)
        }

        goblins.forEach { g ->
            if (!g.alive) return@forEach
            g.vy += Constants.GRAVITY
            val onGroundPrev = g.canJump
            g.updateAiAndAnim(player.x, player.y, onGroundPrev)
            map.moveAndCollide(g)
            g.updateAiAndAnim(player.x, player.y, g.canJump)
            val pBounds = player.bounds()
            if (g.canStartAttackOn(pBounds)) {
                g.tryAttack(player.x + player.w * 0.5f, player.y + player.h * 0.4f)
            }
            g.checkMeleeHit(player, sound, state)
        }

        // ────────────────── Player ARROWS: collisions + update (SNAPSHOTS) ──────────────────
        run {
            val arrowsSnap = arrows.toList()
            val witchesSnap = witches.toList()
            val skeletonsSnap = skeletons.toList()
            val goblinsSnap = goblins.toList()

            for (a in arrowsSnap) {
                for (w in witchesSnap) if (w.alive && a.overlaps(w)) {
                    w.hit(); a.dead = true; sound.playArrowHitEnemy()
                    if (!w.alive) score += w.score
                }
                for (s in skeletonsSnap) if (s.alive && a.overlaps(s)) {
                    s.hit(); a.dead = true; sound.playArrowHitEnemy()
                    if (!s.alive) score += s.score
                }
                for (g in goblinsSnap) if (g.alive && a.overlaps(g)) {
                    g.hit(); a.dead = true; sound.playArrowHitEnemy()
                    if (!g.alive) score += g.score
                }
                if (boss.alive && a.overlaps(boss)) {
                    boss.hit(player.damageDealtToBoss); a.dead = true
                    // +1000 is awarded in boss.onDeath below
                }
                a.update(map.pixelWidth)
            }
        }

        // ────────────────── ENEMY PROJECTILES: update (+sweep for Fireball) + hit player ──────────────────
        run {
            val ebSnap = enemyBullets.toList()
            for (b in ebSnap) {
                val oldX = b.x
                val oldY = b.y

                // Step projectile (Fireball also advances its anim here)
                b.update(map.pixelWidth)

                // Sweep collide Fireball with tiles (avoid tunneling)
                if (b is Fireball && !b.isExploding()) {
                    val dx = b.x - oldX
                    val dy = b.y - oldY
                    val stepLen = (Constants.TILE.toFloat() * 0.25f).coerceAtLeast(2f)
                    val steps = max(1, (max(abs(dx), abs(dy)) / stepLen).toInt())
                    var hit = false
                    var hitX = b.x;
                    var hitY = b.y
                    for (i in 1..steps) {
                        val t = i / steps.toFloat()
                        val tx = oldX + dx * t
                        val ty = oldY + dy * t
                        val rect = RectF(tx, ty, tx + b.w, ty + b.h)
                        if (map.isSolidAtPxRect(rect)) {
                            hit = true; hitX = tx; hitY = ty; break
                        }
                    }
                    if (hit) {
                        b.x = hitX; b.y = hitY
                        b.startExplode(hitPlayer = false)
                        Log.d("GameView", "Fireball hit wall at ${b.x},${b.y}")
                        sound.playFireballExplode()
                    }
                }

                // Hit player?
                if (b.overlaps(player)) {
                    when (b) {
                        is Fireball -> {
                            if (!b.alreadyDamagedPlayer()) {
                                player.hit()
                                sound.play(SoundManager.Sfx.PlayerHurt)
                                sound.playFireballExplode()
                            }
                            b.startExplode(hitPlayer = true)
                        }

                        is LaserBolt -> {
                            if (!b.alreadyDamagedPlayer()) {
                                player.hit()
                                b.markDamagedPlayer()
                                sound.play(SoundManager.Sfx.PlayerHurt)
                            }
                        }

                        is ArmShard -> {
                            player.hit()
                            b.dead = true
                            sound.play(SoundManager.Sfx.PlayerHurt)
                            sound.playRockExplode()
                        }

                        else -> {
                            player.hit()
                            b.dead = true
                            sound.play(SoundManager.Sfx.PlayerHurt)
                        }
                    }
                }
            }

            // Prune enemy projectiles (after iteration)
            enemyBullets.removeAll { b ->
                when (b) {
                    is Fireball -> b.dead
                    is LaserBolt -> b.dead
                    is ArmShard -> {
                        if (map.isSolidAtPxRect(b.bounds())) {
                            Log.d("GameView", "ArmShard hit wall at ${b.x},${b.y}")
                            sound.playRockExplode()
                            true
                        } else b.dead
                    }

                    else -> (b.dead || map.isSolidAtPxRect(b.bounds()))
                }
            }
        }

        // ────────────────── Camera follow ──────────────────
        camera.follow(player.x, player.y, width, height, map.pixelWidth, map.pixelHeight)

        // ────────────────── Cull arrows using current viewport ──────────────────
        run {
            val worldYOffset = (height - map.pixelHeight).coerceAtLeast(0f)
            val margin = 64f
            val left = camera.x - margin
            val right = camera.x + width + margin
            val top = camera.y - worldYOffset - margin
            val bottom = top + height + margin

            arrows.removeAll { a ->
                if (map.isSolidAtPxRect(a.bounds())) {
                    Log.d("GameView", "Culling arrow at ${a.x},${a.y}")
                    sound.playArrowHitWall()
                    true
                } else if (a.dead || (a.x + a.w) < left || a.x > right || (a.y + a.h) < top || a.y > bottom) {
                    true
                } else {
                    false
                }
            }
        }

        // ────────────────── Cleanup dead enemies after animations ──────────────────
        witches.removeAll { it.isDeadAndGone() }
        skeletons.removeAll { it.isDeadAndGone() }
        goblins.removeAll { it.isDeadAndGone() }
    }


    // --- Render frame ---
    private fun drawFrame() {
        val c = holder.lockCanvas() ?: return
        try {
            // (Optional) clear if you like; parallax already paints the screen.
            // c.drawColor(Color.rgb(35, 38, 52))
            map.updateBackground(camera) // NEW: compute layer offsets from camera.x
            map.drawBackground(c)        // NEW: draw 7-layer looping parallax

            // If world is shorter than the view, push it down so the floor sits at the bottom.
            val worldYOffset = (height - map.pixelHeight).coerceAtLeast(0f)

            c.withTranslation(-camera.x, -camera.y + worldYOffset) {
                map.drawTiles(this)
                map.drawDebugTiles(this, camera.x, camera.y, width, height)
                boss.draw(this)
                witches.forEach { it.draw(this) }
                skeletons.forEach { it.draw(this) }
                goblins.forEach { it.draw(this) }
                enemyBullets.forEach { it.draw(this) }
                player.draw(this, gameState = state)
                arrows.forEach { it.draw(this) }
            }

            // draw
            hud.drawHud(
                c, player, player.maxAmmo, player.ammo, player.maxHp, score, highScore
            )

            hud.drawOverlays(c, state, sound.bgmVolumeUi, sound.sfxVolumeUi, score, highScore)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

    private fun resetGame() {
        // --- Audio ---
        stopFootstepsIfAny()
        sound.stopCinematic()                         // stop rock/roar if any
        sound.switchBgm(R.raw.hk_ss_the_mist, true)  // back to default BGM and play

        // --- Cutscene / boss gating flags ---
        awakeningPhase = AwakeningPhase.NONE
        rockDone = false; appearDone = false; roarDone = false
        bossCorpseSettled = false

        // --- Projectiles ---
        arrows.clear()
        enemyBullets.clear()

        // --- Player ---
        initPlayer()          // sets pos/HP and rebinds events (see section 3)

        // --- Enemies ---
        initWitches()
        initSkeletons()
        initGoblins()

        // --- Boss (dormant, not spawned) ---
        initBoss()            // inside: b.startDormant() + settle-to-ground (from earlier)

        // --- Camera & background ---
        camera.x = 0f; camera.y = 0f
        camera.follow(player.x, player.y, width, height, map.pixelWidth, map.pixelHeight)
        map.initBackground(width, height) // reset parallax positions for current surface

        // --- UI/State ---
        state.reset()
        score = 0
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        // 1) Ưu tiên: xử lý click vào các nút Overlay (Pause/GameOver/Victory)
        val handledOverlay = hud.handleTouch(
            event.actionMasked,
            x = x,
            y = y,
            state = state,
            onPauseToggle = {
                state.paused = !state.paused
                if (state.paused) stopFootstepsIfAny()
            },
            onExit = { (context as? android.app.Activity)?.finish() },
            onContinue = { state.paused = false },
            onRetry = { resetGame() },
            onBackToMenu = { (context as? android.app.Activity)?.finish() },
            onSetBgmVolume = { v -> sound.setBgmVolume(v) },
            onSetSfxVolume = { v -> sound.setSfxVolume(v) })
        if (handledOverlay) {
            performClick(); return true
        }

        // 2) Nếu không đụng overlay → chuyển sự kiện cho InputController (HUD: ← → A B II)
        input.onTouchEvent(event, state)
        return true
    }

    private fun initWitches() {
        witches.clear(); witches.addAll(map.witchSpawns.map { (sx, sy) ->
            Witch(
                sx.toFloat(),
                sy.toFloat(),
                context
            )
        })
        witches.forEach { witch ->
            witch.onDeath = { sound.playWitchDie() }
            witch.onHurt = { sound.playWitchHurt() }
            witch.onThrowFireball = { sound.playFireballShoot() }
        }
    }

    private fun initPlayer() {
        player.reset(map.playerStartX.toFloat(), map.playerStartY.toFloat())
        player.onShootArrow = { sound.play(SoundManager.Sfx.PlayerShoot) }
        player.onHurt = { sound.play(SoundManager.Sfx.PlayerHurt) }
        player.onDeathStart = {
            playerIsDying = true
            playerDeathAnimDone = false
            playerDeathSfxDone = false

            // audio
            stopFootstepsIfAny()
            sound.stopCinematic()
            sound.pauseBgm()
            sound.playCinematic(SoundManager.Sfx.PlayerDie, volume = 1f) {
                playerDeathSfxDone = true
            }

            // settle to ground so corpse anim isn’t floating
            settlePlayerToGroundOnce()
        }

        player.onDeathEnd = {
            playerDeathAnimDone = true
        }

        player.onMeleeStrike = { hitbox ->
            var hitSomething = false
            witches.forEach { w ->
                if (w.alive && RectF.intersects(hitbox, w.bounds())) {
                    w.hit(); hitSomething = true
                    if (!w.alive) score += w.score
                }
            }
            skeletons.forEach { s ->
                if (s.alive && RectF.intersects(hitbox, s.bounds())) {
                    s.hit(); hitSomething = true
                    if (!s.alive) score += s.score
                }
            }
            goblins.forEach { g ->
                if (g.alive && RectF.intersects(hitbox, g.bounds())) {
                    g.hit(); hitSomething = true
                    if (!g.alive) score += g.score
                }
            }
            if (boss.alive && RectF.intersects(hitbox, boss.bounds())) {
                boss.hit(player.damageDealtToBoss); hitSomething = true
                // +1000 in boss.onDeath
            }
            if (hitSomething) {
                Log.d("GameView", "Melee hit")
                sound.play(SoundManager.Sfx.ArrowHitEnemy)
            }
        }
    }

    private fun initSkeletons() {
        skeletons.clear(); skeletons.addAll(map.skeletonSpawns.map { (sx, sy) ->
            Skeleton(
                sx.toFloat(),
                sy.toFloat(),
                context
            )
        })
        skeletons.forEach { skeleton ->
            skeleton.onDeath = { sound.playSkeletonDie() }
            skeleton.onHurt = { sound.play(SoundManager.Sfx.SkeletonHurt) }
            skeleton.onAttack = { sound.play(SoundManager.Sfx.SwordSwing) }
        }
    }

    private fun initGoblins() {
        goblins.clear(); goblins.addAll(map.goblinSpawns.map { (sx, sy) ->
            Goblin(
                sx.toFloat(),
                sy.toFloat(),
                context
            )
        })
        goblins.forEach { goblin ->
            goblin.onDeath = { sound.playGoblinDie() }
            goblin.onHurt = { sound.play(SoundManager.Sfx.GoblinHurt) }
            goblin.onAttack = { sound.play(SoundManager.Sfx.DaggerSwing) }
        }
    }

    private fun initBoss() {
        boss = Boss(map.bossStartX.toFloat(), map.bossStartY.toFloat(), context).also { b ->
            b.onHurt = { sound.play(SoundManager.Sfx.GolemHurt) }
            b.onLaserStart = { sound.playShootBeam() }
            b.onThrowArm = { sound.play(SoundManager.Sfx.GolemMissile) }
            b.onMelee = { sound.play(SoundManager.Sfx.GolemMelee) }
            b.onArmorStart = { sound.play(SoundManager.Sfx.ArmorBuff) }
            b.onArmorEnd = { /* stop rumble */ }
            b.onAppearanceDone = {
                appearDone = true
                // If rock cinematic still playing, freeze boss in IDLE until sound finishes
                if (awakeningPhase == AwakeningPhase.ROCK && !rockDone) {
                    boss.setCutsceneFrozen(true, poseIdle = true)
                }
            }
            b.onDeath = {
                // Score for the boss
                score += b.score

                // Gate victory on (1) roar finished AND (2) death anim finished
                bossDeathSfxDone = false
                bossDeathAnimDone = false

                sound.stopBgm()
                sound.stopCinematic() // just in case
                sound.playCinematic(SoundManager.Sfx.BossRoar, volume = 1f) {
                    bossDeathSfxDone = true
                }
            }
            b.startDormant()
        }
    }

    private fun settleBossToGroundOnceForCorpse() {
        // Drop the corpse once so the rock pile rests on the ground
        boss.vy = 24f
        var steps = 0
        while (steps < 240 && !boss.canJump) {
            boss.vy += Constants.GRAVITY
            map.moveAndCollide(boss)
            steps++
        }
        boss.vy = 0f
    }

    private fun settlePlayerToGroundOnce() {
        // Drop to the nearest floor so we don't “freeze in mid-air”
        player.vy = 24f
        var steps = 0
        while (steps < 240 && !player.canJump) {
            player.vy += com.example.game_android.game.core.Constants.GRAVITY
            map.moveAndCollide(player)
            steps++
        }
        player.vy = 0f
    }

    override fun performClick(): Boolean {
        super.performClick(); return true
    }
}