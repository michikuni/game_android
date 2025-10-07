package com.example.game_android

import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

// Preallocate shader per button; RectF reused across draws.
data class MoveButton(
    val title: String,
    val rect: RectF,
    var shader: Shader? = null
)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Show system splash immediately
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(MainView(this))
    }

    inner class MainView(context: MainActivity) : View(context) {
        // Paints are created once.
        private val titleAndTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Buttons & layout caches (no per-frame allocs)
        private val buttons = mutableListOf<MoveButton>()
        private val title = "Soulsong"
        private val credits = "Dang Minh Phuong • Tran Luu Dung • Nguyen Minh Duc"

        // Cached layout numbers
        private var titleTextSize = 72f
        private var creditsTextSize = 28f
        private var titleY = 0f
        private var creditsY = 0f

        // Button colors (constants)
        private val btnStartColor = Color.rgb(70, 70, 90)
        private val btnEndColor   = Color.rgb(30, 30, 45)
        private val btnCorner = 24f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)

            // Scale text sizes with screen (bigger title & credits)
            titleTextSize = maxOf(72f, h * 0.09f)
            creditsTextSize = maxOf(28f, h * 0.038f)
            titleY = h * 0.15f
            creditsY = h * 0.92f

            // Layout buttons
            buttons.clear()
            val bw = w * 0.5f
            val bh = h * 0.12f
            val startX = (w - bw) / 2f
            val spacing = h * 0.04f
            var top = h * 0.25f

            // Only PLAY and EXIT now
            val labels = arrayOf("PLAY", "EXIT")
            for (i in labels.indices) {
                val r = RectF(startX, top, startX + bw, top + bh)
                val b = MoveButton(labels[i], r)
                // Preallocate gradient shader for this rect (no alloc in onDraw)
                b.shader = LinearGradient(
                    r.left, r.top, r.right, r.bottom,
                    btnStartColor, btnEndColor, Shader.TileMode.CLAMP
                )
                buttons.add(b)
                top += bh + spacing
            }
        }

        override fun onDraw(canvas: Canvas) {
            // Background
            canvas.drawColor(Color.rgb(20, 22, 28))

            // Title
            titleAndTextPaint.textAlign = Paint.Align.CENTER
            titleAndTextPaint.textSize = titleTextSize
            titleAndTextPaint.color = Color.WHITE
            canvas.drawText(title, width / 2f, titleY, titleAndTextPaint)

            // Buttons
            titleAndTextPaint.textSize = minOf(width, height) * 0.055f // bigger labels
            val textYOffset = titleAndTextPaint.textSize / 3f
            for (i in 0 until buttons.size) {
                val b = buttons[i]
                buttonPaint.shader = b.shader
                canvas.drawRoundRect(b.rect, btnCorner, btnCorner, buttonPaint)
                canvas.drawText(b.title, b.rect.centerX(), b.rect.centerY() + textYOffset, titleAndTextPaint)
            }
            buttonPaint.shader = null

            // Credits (bigger)
            titleAndTextPaint.textSize = creditsTextSize
            titleAndTextPaint.color = Color.LTGRAY
            canvas.drawText(credits, width / 2f, creditsY, titleAndTextPaint)

            // restore paint color
            titleAndTextPaint.color = Color.WHITE
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x; val y = event.y
                // No lambda alloc; simple loop.
                for (i in 0 until buttons.size) {
                    val b = buttons[i]
                    if (b.rect.contains(x, y)) {
                        when (b.title) {
                            "PLAY" -> startActivity(Intent(this@MainActivity, GameActivity::class.java))
                            "EXIT" -> finish()
                        }
                        break
                    }
                }
            }
            performClick()
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}