package com.watchtogether.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class PlayerGestureController(
    private val activity: Activity,
    private val onSeekRelative: (deltaMs: Long) -> Unit,
    private val onToggleControls: () -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    lateinit var touchOverlay: View
        private set
    lateinit var rippleContainer: FrameLayout
        private set
    lateinit var leftRipple: TextView
        private set
    lateinit var rightRipple: TextView
        private set
    lateinit var hudContainer: LinearLayout
        private set
    lateinit var hudText: TextView
        private set

    private var isDestroyed = false

    // Gestures state
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var isVerticalDragging = false
    private var dragGestureType = 0 // 1: brightness, 2: volume
    private var initialScreenBrightness = 0.5f
    private var initialAudioVolume = 0
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val doubleTapTimeout = 280L

    val singleTapRunnable = Runnable {
        if (!isDestroyed) {
            onToggleControls()
        }
    }

    val hideHudRunnable = Runnable {
        if (!isDestroyed && ::hudContainer.isInitialized) {
            hudContainer.animate().alpha(0f).setDuration(200).withEndAction {
                hudContainer.visibility = View.GONE
            }.start()
        }
    }

    fun buildTouchOverlay(): View {
        touchOverlay = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
        }
        touchOverlay.setOnTouchListener { v, event ->
            val w = v.width.toFloat()
            val h = v.height.toFloat()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    touchStartTime = System.currentTimeMillis()
                    isVerticalDragging = false
                    dragGestureType = 0
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchStartX
                    val dy = touchStartY - event.y // Positive when dragging UP
                    val absDx = abs(dx)
                    val absDy = abs(dy)

                    if (!isVerticalDragging && absDy > 12f * density && absDy > absDx * 1.1f) {
                        isVerticalDragging = true
                        dragGestureType = if (touchStartX < w / 2f) 1 else 2

                        if (dragGestureType == 1) {
                            val lp = activity.window.attributes
                            initialScreenBrightness = if (lp.screenBrightness < 0f) {
                                try {
                                    android.provider.Settings.System.getInt(
                                        activity.contentResolver,
                                        android.provider.Settings.System.SCREEN_BRIGHTNESS
                                    ) / 255f
                                } catch (_: Exception) { 0.5f }
                            } else lp.screenBrightness
                        } else {
                            val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            initialAudioVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        }
                    }

                    if (isVerticalDragging && h > 0) {
                        val deltaNormalized = (dy / (h * 0.65f)).coerceIn(-1.0f, 1.0f)
                        if (dragGestureType == 1) {
                            val targetB = (initialScreenBrightness + deltaNormalized).coerceIn(0.02f, 1.0f)
                            val lp = activity.window.attributes
                            lp.screenBrightness = targetB
                            activity.window.attributes = lp
                            showHud("🔆 Brightness: ${(targetB * 100).toInt()}%")
                        } else {
                            val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val deltaSteps = (deltaNormalized * maxVol).toInt()
                            val targetVol = (initialAudioVolume + deltaSteps).coerceIn(0, maxVol)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            val pct = if (maxVol > 0) (targetVol * 100) / maxVol else 0
                            showHud(if (targetVol == 0) "🔇 Muted" else "🔊 Volume: $pct%")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    val totalTime = now - touchStartTime
                    val totalDist = abs(event.x - touchStartX) + abs(event.y - touchStartY)

                    if (isVerticalDragging) {
                        isVerticalDragging = false
                        hideHudDelayed()
                    } else if (totalDist < 25f * density && totalTime < 350) {
                        val timeSinceLastTap = now - lastTapTime
                        val distFromLastTap = abs(event.x - lastTapX) + abs(event.y - lastTapY)

                        if (timeSinceLastTap < doubleTapTimeout && distFromLastTap < 50f * density) {
                            // DOUBLE TAP
                            ui.removeCallbacks(singleTapRunnable)
                            lastTapTime = now // Allow successive rapid taps to chain seamlessly (+10s, +20s, +30s)
                            if (event.x < w / 2f) {
                                onSeekRelative(-10000L)
                                showRipple(false)
                                showHud("« 10s")
                            } else {
                                onSeekRelative(10000L)
                                showRipple(true)
                                showHud("10s »")
                            }
                            hideHudDelayed()
                        } else {
                            lastTapTime = now
                            lastTapX = event.x
                            lastTapY = event.y
                            ui.removeCallbacks(singleTapRunnable)
                            ui.postDelayed(singleTapRunnable, doubleTapTimeout)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isVerticalDragging) {
                        isVerticalDragging = false
                        hideHudDelayed()
                    }
                    true
                }
                else -> false
            }
        }
        return touchOverlay
    }

    fun buildRippleContainer(): FrameLayout {
        rippleContainer = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        leftRipple = TextView(activity).apply {
            text = "« 10s"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#CC1E293B"), 18f, Color.parseColor("#3B82F6"), 1)
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                marginStart = (28 * density).toInt()
            }
        }
        rightRipple = TextView(activity).apply {
            text = "10s »"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#CC1E293B"), 18f, Color.parseColor("#3B82F6"), 1)
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                marginEnd = (28 * density).toInt()
            }
        }
        rippleContainer.addView(leftRipple)
        rippleContainer.addView(rightRipple)
        return rippleContainer
    }

    fun buildHudContainer(): LinearLayout {
        hudContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (16 * density).toInt()
            }
            isClickable = false
            isFocusable = false
        }
        hudText = TextView(activity).apply {
            text = "HUD"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = UIUtils.roundedBackground(density, Color.parseColor("#E60F172A"), 14f, Color.parseColor("#334155"), 1)
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
        }
        hudContainer.addView(hudText)
        return hudContainer
    }

    fun showHud(text: String) {
        ui.post {
            if (isDestroyed || !::hudContainer.isInitialized) return@post
            hudText.text = text
            hudContainer.alpha = 1f
            hudContainer.visibility = View.VISIBLE
            ui.removeCallbacks(hideHudRunnable)
            ui.postDelayed(hideHudRunnable, 1200)
        }
    }

    fun hideHudDelayed() {
        ui.removeCallbacks(hideHudRunnable)
        ui.postDelayed(hideHudRunnable, 1200)
    }

    fun showRipple(isForward: Boolean) {
        val target = if (isForward) rightRipple else leftRipple
        target.visibility = View.VISIBLE
        target.alpha = 0f
        target.scaleX = 0.8f
        target.scaleY = 0.8f
        target.animate()
            .alpha(1f)
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(160)
            .withEndAction {
                target.animate()
                    .alpha(0f)
                    .scaleX(1.3f)
                    .scaleY(1.3f)
                    .setDuration(240)
                    .withEndAction {
                        target.visibility = View.GONE
                        target.scaleX = 1f
                        target.scaleY = 1f
                    }
                    .start()
            }
            .start()
    }

    fun destroy() {
        isDestroyed = true
        ui.removeCallbacks(singleTapRunnable)
        ui.removeCallbacks(hideHudRunnable)
    }
}

