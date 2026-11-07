package com.watchtogether.app.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

class FloatingEmojiManager(
    private val context: Context,
    private val emojiContainer: FrameLayout
) {
    private val ui = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    fun spawn(emoji: String) {
        ui.post {
            val tv = TextView(context).apply {
                text = emoji
                textSize = 38f
                alpha = 0f
                setShadowLayer(10f * density, 0f, 3f * density, Color.parseColor("#B3000000"))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                }
            }
            emojiContainer.addView(tv)

            val containerW = emojiContainer.width.takeIf { it > 0 } ?: (300 * density).toInt()
            val containerH = emojiContainer.height.takeIf { it > 0 } ?: (200 * density).toInt()

            val startX = (containerW * (0.20f + (Math.random().toFloat() * 0.65f))).coerceIn(15f * density, (containerW - 55 * density).toFloat())
            val startY = containerH * 0.82f
            tv.x = startX
            tv.y = startY

            val driftX = (Math.random().toFloat() - 0.5f) * 80f * density
            val targetY = startY - (containerH * 0.72f)

            tv.scaleX = 0.4f
            tv.scaleY = 0.4f

            // 1. Pop in clearly (350ms)
            tv.animate()
                .alpha(1f)
                .scaleX(1.35f)
                .scaleY(1.35f)
                .setDuration(350)
                .withEndAction {
                    // 2. Slow drift upwards staying fully visible (2600ms)
                    tv.animate()
                        .x(startX + driftX)
                        .y(targetY)
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .setDuration(2600)
                        .withEndAction {
                            // 3. Gentle fade out at the very top (900ms)
                            tv.animate()
                                .alpha(0f)
                                .y(targetY - (25f * density))
                                .scaleX(0.85f)
                                .scaleY(0.85f)
                                .setDuration(900)
                                .withEndAction {
                                    emojiContainer.removeView(tv)
                                }
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }

    fun clear() {
        ui.post {
            emojiContainer.removeAllViews()
        }
    }
}

