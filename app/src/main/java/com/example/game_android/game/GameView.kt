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
import com.example.game_android.game.entities.PhysicsBody
import com.example.game_android.game.entities.PickupItem
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
import kotlin.math.sqrt

/**
 * Main game view & loop.
 *
 * Responsibilities:
 *  • Orchestrate the fixed-timestep loop (60 FPS)
 *  • Route input, physics, AI updates, collisions
 *  • Manage audio flows (BGM/SFX, cutscenes, footsteps)
 *  • Render background, world, HUD & overlays
 *  • Handle round reset / victory / death gates
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    // ─────────────────────────────────────────────────────────────────────────────
    // Loop threading
    // ─────────────────────────────────────────────────────────────────────────────
    private var thread: Thread? = null

    @Volatile
    private var running = false

    // ─────────────────────────────────────────────────────────────────────────────
    // Core singletons
    // ─────────────────────────────────────────────────────────────────────────────
    private val camera = Camera()
    private val input = InputController(this, context)
    private val state = GameState()
    private val sound = SoundManager(context)

    // ─────────────────────────────────────────────────────────────────────────────
    // World & Entities
    // ─────────────────────────────────────────────────────────────────────────────
    private val map = TileMap(context)

    private val player = Player(map.playerStartX.toFloat(), map.playerStartY.toFloat(), context)
    private var boss = Boss(map.bossStartX.toFloat(), map.bossStartY.toFloat(), context)

    private val witches = mutableListOf<Witch>()
    private val skeletons = mutableListOf<Skeleton>()
    private val goblins = mutableListOf<Goblin>()

    private val arrows = mutableListOf<Projectile>()       // player projectiles
    private val enemyBullets = mutableListOf<Projectile>() // enemy projectiles

    private val pickups = mutableListOf<PickupItem>()      // hearts / ammo

    // ─────────────────────────────────────────────────────────────────────────────
    // Rendering helpers
    // ─────────────────────────────────────────────────────────────────────────────
    private val hud = HudRenderer(input, context)

    // ─────────────────────────────────────────────────────────────────────────────
    // Footstep loop tracking
    // ─────────────────────────────────────────────────────────────────────────────
    private var footstepStreamId: Int = 0

    // ─────────────────────────────────────────────────────────────────────────────
    // Player death gating
    // ─────────────────────────────────────────────────────────────────────────────
    private var playerIsDying = false
    private var playerDeathAnimDone = false
    private var playerDeathSfxDone = false

    // ─────────────────────────────────────────────────────────────────────────────
    // Boss awakening cutscene states
    // ─────────────────────────────────────────────────────────────────────────────
    private enum class AwakeningPhase { NONE, ROCK, ROAR }

    private var awakeningPhase = AwakeningPhase.NONE
    private var rockDone = false
    private var appearDone = false
    private var roarDone = false
    private var bossCorpseSettled = false

    // ─────────────────────────────────────────────────────────────────────────────
    // Scoring (persisted high score)
    // ─────────────────────────────────────────────────────────────────────────────
    private val prefs = context.getSharedPreferences("scores", Context.MODE_PRIVATE)
    private var score = 0
    private var highScore = prefs.getInt("highScore", 0)

    // ─────────────────────────────────────────────────────────────────────────────
    // Boss death --> victory gating
    // ─────────────────────────────────────────────────────────────────────────────
    private var bossDeathAnimDone = false
    private var bossDeathSfxDone = false

    // ─────────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────────
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
        initPickups()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Surface callbacks
    // ─────────────────────────────────────────────────────────────────────────────
    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this).apply { start() }

        // Set global volume scaling (master attenuation) and start BGM
        sound.setBgmMasterAttenuation(0.1f)
        sound.setSfxMasterAttenuation(0.5f)
        sound.startBgmIfNeeded()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try {
            thread?.join()
        } catch (_: InterruptedException) {
        }
        sound.release()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        map.initBackground(width, height)
        input.layout(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        input.layout(w, h)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Main loop (fixed-step 60 FPS)
    // ─────────────────────────────────────────────────────────────────────────────
    override fun run() {
        var last = System.nanoTime()
        val step = 1_000_000_000.0 / 60.0
        var acc = 0.0

        while (running) {
            val now = System.nanoTime()
            acc += (now - last)
            last = now

            var updated = false
            while (acc >= step) {
                update()
                acc -= step
                updated = true
            }
            if (updated) drawFrame()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Update
    // ─────────────────────────────────────────────────────────────────────────────
    private fun update() {
        if (state.anyOverlay()) return
        if (width <= 0 || height <= 0) return

        // Death & Victory gates
        if (handlePlayerDeathFlowIfAny()) return
        if (handleVictoryFreezeIfAny()) return

        // Awakening cutscene (ROCK --> ROAR), freezes most of the world
        if (handleBossAwakeningCutsceneIfAny()) return

        // Input --> movement & jumping (blocked if attacking)
        applyHorizontalInputToPlayer()
        handleJumpInputIfAny()

        // Footsteps loop (grounded + moving + not attacking)
        updateFootstepsLoop()

        // Physics & collisions (player first)
        applyGravity(player)
        map.moveAndCollide(player)

        // Pickups (hearts / arrows)
        updatePickupsAndCollection()

        // Combat input (shoot / melee)
        handleCombatInput()

        // Summon trigger --> start cutscene
        maybeTriggerBossSummonCutscene()

        // Boss update (dormant/appearing vs active vs dying)
        updateBossBranch()

        // Enemies update (AI + movement + melee checks)
        updateEnemies()

        // Projectiles: player arrows --> enemies/boss; enemy bullets --> player
        updateProjectiles()

        // Camera follow
        camera.follow(player.x, player.y, width, height, map.pixelWidth, map.pixelHeight)

        // Cull arrows outside viewport / hitting tiles
        // Note that arrow culling depends on the camera/viewport (a rendering concern), not on
        // projectile physics, so we will not do it in updateProjectiles(). Also, we do it after
        // camera update so that the culling logic can use the latest camera position.
        cullArrows()

        // Cleanup dead enemies & scoring
        cleanupDeadEnemiesAndScore()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Death & Victory gates
    // ─────────────────────────────────────────────────────────────────────────────
    private fun handlePlayerDeathFlowIfAny(): Boolean {
        if (!playerIsDying) return false
        if (playerDeathAnimDone && playerDeathSfxDone) {
            playerIsDying = false
            resetGame()
        }
        return true
    }

    private fun handleVictoryFreezeIfAny(): Boolean {
        if (!state.victory) return false
        stopFootstepsIfAny()
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Boss awakening cutscene flows (ROCK → ROAR)
    // ─────────────────────────────────────────────────────────────────────────────
    private fun handleBossAwakeningCutsceneIfAny(): Boolean {
        if (awakeningPhase == AwakeningPhase.NONE) return false

        // Only boss ticks; player remains idle & frozen for safety
        boss.updateAiAndAnim(
            playerCenterX(),
            playerCenterY(),
            onGround = false,
            tileSolidAtPx = { false },
            enemyProjectiles = enemyBullets,
            playerBounds = player.bounds(),
            onDamagePlayer = { /* disabled during cutscene */ })

        stopFootstepsIfAny()
        player.vx = 0f; player.vy = 0f

        when (awakeningPhase) {
            AwakeningPhase.ROCK -> {
                if (rockDone && appearDone) {
                    awakeningPhase = AwakeningPhase.ROAR
                    roarDone = false

                    boss.setCutsceneFrozen(true, poseIdle = true)
                    sound.switchBgm(R.raw.hk_ss_phantom, autoplay = true)
                    sound.playCinematic(SoundManager.Sfx.BossRoar, volume = 0.3f) {
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
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Horizontal input
    // ─────────────────────────────────────────────────────────────────────────────
    private fun applyHorizontalInputToPlayer() {
        val accel = 0.8f
        when {
            input.left && !input.right && !player.isAttacking -> player.vx =
                max(player.vx - accel, -Constants.PLAYER_MAX_SPD)

            input.right && !input.left && !player.isAttacking -> player.vx =
                min(player.vx + accel, Constants.PLAYER_MAX_SPD)

            else -> {
                player.vx *= 0.8f
                if (abs(player.vx) < 0.2f) player.vx = 0f
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Jump input
    // ─────────────────────────────────────────────────────────────────────────────
    private fun handleJumpInputIfAny() {
        if (input.jumpPressed(player) && player.canJump && !player.isAttacking) {
            player.vy = -Constants.JUMP_VELOCITY
            player.canJump = false
            player.onJumpImpulse()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Gravity helper
    // ─────────────────────────────────────────────────────────────────────────────
    private fun applyGravity(body: PhysicsBody) {
        body.vy += Constants.GRAVITY
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Footsteps loop
    // ─────────────────────────────────────────────────────────────────────────────
    private fun updateFootstepsLoop() {
        val speed = abs(player.vx)
        val isWalking = player.canJump && speed > 0.2f && !player.isAttacking

        if (isWalking) {
            val vol = 0.35f + 0.45f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
            val rate = 0.9f + 0.3f * (speed / Constants.PLAYER_MAX_SPD).coerceIn(0f, 1f)
            if (footstepStreamId == 0) {
                footstepStreamId =
                    sound.playLoop(SoundManager.Sfx.PlayerWalk, volume = vol, rate = rate)
            } else {
                sound.setLoopVolume(footstepStreamId, vol)
                sound.setLoopRate(footstepStreamId, rate)
            }
        } else {
            stopFootstepsIfAny()
        }
    }

    private fun stopFootstepsIfAny() {
        if (footstepStreamId != 0) {
            sound.stopLoop(footstepStreamId)
            footstepStreamId = 0
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Pickups
    // ─────────────────────────────────────────────────────────────────────────────
    private fun updatePickupsAndCollection() {
        pickups.forEach { it.update() }

        val pb = player.bounds()
        //return@label only return from the lambda, not from the enclosing function
        pickups.removeAll { p ->
            if (!RectF.intersects(pb, p.bounds())) return@removeAll false
            when (p.type) {
                PickupItem.Type.HEART -> {
                    if (player.hp < player.maxHp) {
                        player.hp = (player.hp + 1).coerceAtMost(player.maxHp)
                        sound.playPickPotion()
                        true
                    } else false
                }

                PickupItem.Type.ARROWS -> {
                    if (player.ammo >= player.maxAmmo) {
                        false
                    } else {
                        player.addAmmo(3) // +3 arrows
                        sound.playPickAmmo()
                        true
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Combat input
    // ─────────────────────────────────────────────────────────────────────────────
    private fun handleCombatInput() {
        if (input.fire && !player.isAttacking && player.ammo > 0) {
            player.tryShoot(arrows)
        }
        player.setMeleeHeld(input.melee)
        if (input.melee && !player.isAttacking) {
            player.tryMelee()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Summon trigger
    // ─────────────────────────────────────────────────────────────────────────────
    private fun maybeTriggerBossSummonCutscene() {
        if (awakeningPhase != AwakeningPhase.NONE || boss.summoned) return

        val dx = playerCenterX() - bossCenterX()
        val dy = playerCenterY() - bossCenterY()
        if (sqrt(dx * dx + dy * dy) <= boss.summonRadius()) {
            boss.triggerSummon()

            // Clear screen entities/projectiles
            arrows.clear()
            enemyBullets.clear()
            witches.clear()
            skeletons.clear()
            goblins.clear()

            // Begin cutscene: ROCK sound while boss appears
            awakeningPhase = AwakeningPhase.ROCK
            rockDone = false; appearDone = false; roarDone = false

            stopFootstepsIfAny()
            sound.pauseBgm()
            sound.playCinematic(SoundManager.Sfx.RockCinematic, volume = 1f) { rockDone = true }

            // Player forced idle + settle on ground
            player.forceIdleForCutscene(clearHurtBlink = true)
            settlePlayerToGroundOnce()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Boss branch
    // ─────────────────────────────────────────────────────────────────────────────
    private fun updateBossBranch() {
        when {
            !boss.summoned -> {
                boss.updateAiAndAnim(
                    playerCenterX(),
                    playerCenterY(),
                    onGround = false,
                    tileSolidAtPx = { false },
                    enemyProjectiles = enemyBullets,
                    playerBounds = player.bounds(),
                    onDamagePlayer = { /* no damage before summon */ })
            }

            boss.alive -> {
                val onGroundPrev = boss.canJump
                applyGravity(boss)
                boss.updateAiAndAnim(
                    playerCenterX(),
                    playerCenterY(),
                    onGroundPrev,
                    tileSolidAtPx = { r -> map.isSolidAtPxRect(r) },
                    enemyProjectiles = enemyBullets,
                    playerBounds = player.bounds(),
                    onDamagePlayer = { player.hit() })
                map.moveAndCollide(boss)
                bossCorpseSettled = false
            }

            else -> { // dying / dead
                if (boss.isDeadAndGone()) bossDeathAnimDone = true

                // Gate victory by both SFX and anim having finished
                if (!state.victory && bossDeathAnimDone && bossDeathSfxDone) {
                    stopFootstepsIfAny()
                    updateHighScoreIfNeeded()
                    state.victory = true

                    sound.pauseBgm()
                    sound.playCinematic(SoundManager.Sfx.Victory, volume = 0.5f) { /* once */ }
                }

                if (!bossCorpseSettled) {
                    settleBossToGroundOnceForCorpse()
                    bossCorpseSettled = true
                }

                boss.updateAiAndAnim(
                    playerCenterX(),
                    playerCenterY(),
                    onGround = true,
                    tileSolidAtPx = { false },
                    enemyProjectiles = enemyBullets,
                    playerBounds = player.bounds(),
                    onDamagePlayer = { /* N/A */ })
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Enemies
    // ─────────────────────────────────────────────────────────────────────────────
    private fun updateEnemies() {
        witches.forEach { w ->
            if (!w.alive) return@forEach
            val onGroundPrev = w.canJump
            applyGravity(w)
            w.updateAiAndAnim(player.x, player.y, onGroundPrev)
            map.moveAndCollide(w)
            w.updateAiAndAnim(player.x, player.y, w.canJump)
            w.tryShootFireball(enemyBullets, playerCenterX(), player.y + player.h * 0.4f)
        }

        skeletons.forEach { s ->
            if (!s.alive) return@forEach
            val onGroundPrev = s.canJump
            applyGravity(s)
            s.updateAiAndAnim(player.x, player.y, onGroundPrev)
            map.moveAndCollide(s)
            s.updateAiAndAnim(player.x, player.y, s.canJump)

            if (s.canStartAttackOn(player.bounds())) {
                s.tryAttack(playerCenterX(), player.y + player.h * 0.4f)
            }
            s.checkMeleeHit(player, sound, state)
        }

        goblins.forEach { g ->
            if (!g.alive) return@forEach
            val onGroundPrev = g.canJump
            applyGravity(g)
            g.updateAiAndAnim(player.x, player.y, onGroundPrev)
            map.moveAndCollide(g)
            g.updateAiAndAnim(player.x, player.y, g.canJump)

            if (g.canStartAttackOn(player.bounds())) {
                g.tryAttack(playerCenterX(), player.y + player.h * 0.4f)
            }
            g.checkMeleeHit(player, sound, state)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Projectiles & collisions
    // ─────────────────────────────────────────────────────────────────────────────
    private fun updateProjectiles() {
        // Player arrows --> enemies + boss
        run {
            val arrowsSnap = arrows.toList()
            val witchesSnap = witches.toList()
            val skeletonsSnap = skeletons.toList()
            val goblinsSnap = goblins.toList()

            for (a in arrowsSnap) {
                for (w in witchesSnap) if (w.alive && a.overlaps(w)) {
                    w.hit(); a.dead = true; sound.playArrowHitEnemy()
                }
                for (s in skeletonsSnap) if (s.alive && a.overlaps(s)) {
                    s.hit(); a.dead = true; sound.playArrowHitEnemy()
                }
                for (g in goblinsSnap) if (g.alive && a.overlaps(g)) {
                    g.hit(); a.dead = true; sound.playArrowHitEnemy()
                }
                if (boss.alive && a.overlaps(boss)) {
                    boss.hit(player.damageDealtToBoss); a.dead = true
                }
                a.update(map.pixelWidth)
            }
        }

        // Enemy projectiles --> player (with Fireball sweep against tiles)
        run {
            val ebSnap = enemyBullets.toList()
            for (b in ebSnap) {
                val oldX = b.x
                val oldY = b.y
                b.update(map.pixelWidth)

                if (b is Fireball && !b.isExploding()) {
                    sweepFireballAgainstTiles(b, oldX, oldY)
                }

                // Hit player?
                if (b.overlaps(player)) {
                    when (b) {
                        is Fireball -> {
                            if (!b.alreadyDamagedPlayer()) {
                                player.hit()
                                sound.playFireballExplode()
                            }
                            b.startExplode(hitPlayer = true)
                        }

                        is LaserBolt -> {
                            if (!b.alreadyDamagedPlayer()) {
                                player.hit()
                                b.markDamagedPlayer()
                            }
                        }

                        is ArmShard -> {
                            player.hit()
                            b.dead = true
                            sound.playRockExplode()
                        }

                        else -> {
                            player.hit(); b.dead = true
                        }
                    }
                }
            }

            // Prune enemy projectiles
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
    }

    private fun sweepFireballAgainstTiles(b: Fireball, oldX: Float, oldY: Float) {
        val dx = b.x - oldX
        val dy = b.y - oldY
        val stepLen = (Constants.TILE.toFloat() * 0.25f).coerceAtLeast(2f)
        val steps = max(1, (max(abs(dx), abs(dy)) / stepLen).toInt())
        var hit = false
        var hitX = b.x
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Camera culling for arrows
    // ─────────────────────────────────────────────────────────────────────────────
    private fun cullArrows() {
        val worldYOffset = (height - map.pixelHeight).coerceAtLeast(0f)
        val margin = 64f
        val left = camera.x - margin
        val right = camera.x + width + margin
        val top = camera.y - worldYOffset - margin
        val bottom = top + height + margin

        arrows.removeAll { a ->
            when {
                map.isSolidAtPxRect(a.bounds()) -> {
                    Log.d("GameView", "Culling arrow at ${a.x},${a.y}")
                    sound.playArrowHitWall()
                    true
                }

                a.dead || (a.x + a.w) < left || a.x > right || (a.y + a.h) < top || a.y > bottom -> true
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Cleanup & scoring
    // ─────────────────────────────────────────────────────────────────────────────
    private fun cleanupDeadEnemiesAndScore() {
        score += witches.count { it.isDeadAndGone() } * Constants.WITCH_SCORE
        score += skeletons.count { it.isDeadAndGone() } * Constants.SKELETON_SCORE
        score += goblins.count { it.isDeadAndGone() } * Constants.GOBLIN_SCORE

        witches.removeAll { it.isDeadAndGone() }
        skeletons.removeAll { it.isDeadAndGone() }
        goblins.removeAll { it.isDeadAndGone() }
    }

    private fun updateHighScoreIfNeeded() {
        if (score > highScore) {
            highScore = score
            prefs.edit { putInt("highScore", highScore) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────────
    private fun drawFrame() {
        val c = holder.lockCanvas() ?: return
        try {
            // Parallax background
            map.updateBackground(camera)
            map.drawBackground(c)

            // If world shorter than view, seat the floor to bottom
            val worldYOffset = (height - map.pixelHeight).coerceAtLeast(0f)

            c.withTranslation(-camera.x, -camera.y + worldYOffset) {
                map.drawTiles(this)
                map.drawDebugTiles(this, camera.x, camera.y, width, height)

                // Entities (draw order: pickups --> boss/enemies --> player --> arrows --> enemy bullets)
                pickups.forEach { it.draw(this) }

                boss.draw(this)
                witches.forEach { it.draw(this) }
                skeletons.forEach { it.draw(this) }
                goblins.forEach { it.draw(this) }

                enemyBullets.forEach { it.draw(this) }
                player.draw(this, gameState = state)
                arrows.forEach { it.draw(this) }
            }

            // HUD & overlays
            hud.drawHud(c, player, player.maxAmmo, player.ammo, player.maxHp, score, highScore)
            hud.drawOverlays(c, state, sound.bgmVolumeUi, sound.sfxVolumeUi, score, highScore)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Round reset
    // ─────────────────────────────────────────────────────────────────────────────
    private fun resetGame() {
        // Audio
        stopFootstepsIfAny()
        sound.stopCinematic()
        sound.switchBgm(R.raw.hk_ss_the_mist, true)

        // Cutscene / boss flags
        awakeningPhase = AwakeningPhase.NONE
        rockDone = false; appearDone = false; roarDone = false
        bossCorpseSettled = false

        // Projectiles
        arrows.clear()
        enemyBullets.clear()

        // Player & mobs
        initPlayer()
        initWitches()
        initSkeletons()
        initGoblins()
        initBoss()
        initPickups()

        // Camera & background
        camera.x = 0f; camera.y = 0f
        camera.follow(player.x, player.y, width, height, map.pixelWidth, map.pixelHeight)
        map.initBackground(width, height)

        // UI/State
        state.reset()
        score = 0
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Input routing (touch)
    // ─────────────────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        // 1) Overlays consume first
        val handledOverlay = hud.handleTouch(
            event.actionMasked,
            x,
            y,
            state,
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
            performClick()
            return true
        }

        // 2) Otherwise, route to in-game input controls
        input.onTouchEvent(event, state)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Entity initializers
    // ─────────────────────────────────────────────────────────────────────────────
    private fun initWitches() {
        witches.clear()
        witches.addAll(map.witchSpawns.map { (sx, sy) ->
            Witch(
                sx.toFloat(), sy.toFloat(), context
            )
        })
        witches.forEach { w ->
            w.onDeath = { sound.playWitchDie() }
            w.onHurt = { sound.playWitchHurt() }
            w.onThrowFireball = { sound.playFireballShoot() }
        }
    }

    private fun initSkeletons() {
        skeletons.clear()
        skeletons.addAll(map.skeletonSpawns.map { (sx, sy) ->
            Skeleton(
                sx.toFloat(), sy.toFloat(), context
            )
        })
        skeletons.forEach { s ->
            s.onDeath = { sound.playSkeletonDie() }
            s.onHurt = { sound.playSkeletonHurt() }
            s.onAttack = { sound.playSwordSwing() }
        }
    }

    private fun initGoblins() {
        goblins.clear()
        goblins.addAll(map.goblinSpawns.map { (sx, sy) ->
            Goblin(
                sx.toFloat(), sy.toFloat(), context
            )
        })
        goblins.forEach { g ->
            g.onDeath = { sound.playGoblinDie() }
            g.onHurt = { sound.playGoblinHurt() }
            g.onAttack = { sound.playDaggerSwing() }
        }
    }

    private fun initPickups() {
        pickups.clear()
        map.heartSpawns.forEach { (sx, sy) ->
            pickups += PickupItem(PickupItem.Type.HEART, sx.toFloat(), sy.toFloat(), context)
        }
        map.ammoSpawns.forEach { (sx, sy) ->
            pickups += PickupItem(PickupItem.Type.ARROWS, sx.toFloat(), sy.toFloat(), context)
        }
    }

    private fun initPlayer() {
        player.reset(map.playerStartX.toFloat(), map.playerStartY.toFloat())

        player.onBowLoading = { sound.play(SoundManager.Sfx.BowLoading) }
        player.onShootArrow = { sound.play(SoundManager.Sfx.PlayerShoot) }
        player.onHurt = { sound.playPlayerHurt() }

        player.onDeathStart = {
            playerIsDying = true
            playerDeathAnimDone = false
            playerDeathSfxDone = false

            stopFootstepsIfAny()
            sound.stopCinematic()
            sound.pauseBgm()
            sound.playCinematic(SoundManager.Sfx.PlayerDie, volume = 0.5f) {
                playerDeathSfxDone = true
            }

            settlePlayerToGroundOnce()
        }
        player.onDeathEnd = { playerDeathAnimDone = true }

        player.onArrowMeleeSwing = { sound.play(SoundManager.Sfx.ArcherMelee1) }
        player.onBowMeleeSpin = { sound.play(SoundManager.Sfx.ArcherMelee2) }

        player.onMeleeStrike = { hitbox ->
            var hit = false
            witches.forEach {
                if (it.alive && RectF.intersects(hitbox, it.bounds())) {
                    it.hit(); hit = true
                }
            }
            skeletons.forEach {
                if (it.alive && RectF.intersects(hitbox, it.bounds())) {
                    it.hit(); hit = true
                }
            }
            goblins.forEach {
                if (it.alive && RectF.intersects(hitbox, it.bounds())) {
                    it.hit(); hit = true
                }
            }
            if (boss.alive && RectF.intersects(hitbox, boss.bounds())) {
                boss.hit(player.damageDealtToBoss); hit = true
            }
            if (hit) {
                Log.d("GameView", "Melee hit")
                sound.playSwordHitEnemy()
            }
        }
    }

    private fun initBoss() {
        boss = Boss(map.bossStartX.toFloat(), map.bossStartY.toFloat(), context).also { b ->
            b.onHurt = { sound.play(SoundManager.Sfx.GolemHurt) }
            b.onLaserStart = { sound.playShootBeam() }
            b.onThrowArm = { sound.play(SoundManager.Sfx.GolemMissile) }
            b.onMelee = { sound.play(SoundManager.Sfx.GolemMelee) }
            b.onArmorStart = { sound.play(SoundManager.Sfx.ArmorBuff) }
            b.onArmorEnd = { /* rumble stop if any */ }
            b.onAppearanceDone = {
                appearDone = true
                if (awakeningPhase == AwakeningPhase.ROCK && !rockDone) {
                    boss.setCutsceneFrozen(true, poseIdle = true)
                }
            }
            b.onDeath = {
                // award score for boss
                score += Constants.GOLEM_SCORE

                bossDeathSfxDone = false
                bossDeathAnimDone = false

                sound.stopBgm()
                sound.stopCinematic()
                sound.playCinematic(SoundManager.Sfx.GolemDie, volume = 1f) {
                    bossDeathSfxDone = true
                }
            }
            b.startDormant()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // One-off settling helpers (prevent floating corpses / mid-air freezes)
    // ─────────────────────────────────────────────────────────────────────────────
    private fun settleBossToGroundOnceForCorpse() {
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
        player.vy = 24f
        var steps = 0
        while (steps < 240 && !player.canJump) {
            player.vy += Constants.GRAVITY
            map.moveAndCollide(player)
            steps++
        }
        player.vy = 0f
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Small coordinate helpers
    // ─────────────────────────────────────────────────────────────────────────────
    private fun playerCenterX() = player.x + player.w * 0.5f
    private fun playerCenterY() = player.y + player.h * 0.5f
    private fun bossCenterX() = boss.x + boss.w * 0.5f
    private fun bossCenterY() = boss.y + boss.h * 0.55f
}