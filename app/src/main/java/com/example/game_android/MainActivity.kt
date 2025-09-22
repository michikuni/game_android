package com.example.game_android

import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity


data class MoveButton(val title: String, val rect: RectF)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(MainView(this))
    }

    inner class MainView(context: MainActivity) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 56f
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val buttons = mutableListOf<MoveButton>()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            buttons.clear()
            val bw = w * 0.5f
            val bh = h * 0.12f
            val startX = (w - bw) / 2f
            val spacing = h * 0.04f
            var top = h * 0.25f
            listOf("PLAY", "GUIDE", "MAP", "EXIT").forEach { label ->
                buttons.add(MoveButton(label, RectF(startX, top, startX + bw, top + bh)))
                top += bh + spacing
            }
        }


        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(20, 22, 28))
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Contra", width/2f, height*0.15f, paint)
            buttons.forEachIndexed { idx, b ->
                btnPaint.shader = LinearGradient(b.rect.left, b.rect.top, b.rect.right, b.rect.bottom,
                    Color.rgb(70,70,90), Color.rgb(30,30,45), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(b.rect, 24f, 24f, btnPaint)
                paint.textSize = 44f
                canvas.drawText(b.title, b.rect.centerX(), b.rect.centerY()+16f, paint)
            }
            paint.textSize = 24f
            paint.color = Color.LTGRAY
            canvas.drawText("Dang Minh Phuong • Tran Luu Dung • Nguyen Minh Duc", width/2f, height*0.92f, paint)
            paint.color = Color.WHITE
        }


        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x; val y = event.y
                buttons.firstOrNull { it.rect.contains(x, y) }?.let { b ->
                    when (b.title) {
                        "PLAY" -> startActivity(Intent(this@MainActivity, GameActivity::class.java))
                        "GUIDE" -> showGuide()
                        "MAP" -> showMap()
                        "EXIT" -> finish()
                    }
                }
            }
            performClick()
            return true
        }
        override fun performClick(): Boolean {
            super.performClick()
            // Thêm xử lý khi click nếu cần
            return true
        }

        private fun showGuide() {
// Simple overlay dialog
            val dialog = android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Guide")
                .setMessage("Controls: ← → move, A=Jump, B=Fire (buttons on screen). One-way platforms '^' can be jumped from below. Boss needs 30 hits.")
                .setPositiveButton("OK", null)
                .create()
            dialog.show()
        }


        private fun showMap() {
            val dialog = android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Map")
                .setMessage("500 columns x 20 rows. Ground '#' and platforms '^'. Boss 'B' at far right.")
                .setPositiveButton("OK", null)
                .create()
            dialog.show()
        }
    }
}