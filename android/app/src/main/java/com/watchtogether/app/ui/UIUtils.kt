package com.watchtogether.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.absoluteValue

object UIUtils {

    // Modern Color Palette Constants
    object Colors {
        const val BG_CANVAS = "#07090E"       // Deep Obsidian / Cinema Black
        const val CARD_BG = "#0D1322"         // Frosted Glass Dark
        const val CARD_BORDER = "#1E293B"     // Subtle Slate Border
        const val CARD_BORDER_GLOW = "#38BDF8"// Luminous Accent Border
        const val BRAND_PRIMARY = "#4F46E5"   // Royal Indigo
        const val BRAND_SECONDARY = "#7C3AED" // Electric Violet
        const val ACCENT_CYAN = "#06B6D4"     // Neon Cyan
        const val ACCENT_EMERALD = "#10B981"  // Vibrant Emerald
        const val ACCENT_ROSE = "#F43F5E"     // Vibrant Rose
        const val ACCENT_AMBER = "#F59E0B"    // Warm Amber
        const val TEXT_WHITE = "#FFFFFF"
        const val TEXT_MUTED = "#94A3B8"
        const val TEXT_SUBTLE = "#64748B"
    }

    fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun roundedBackground(
        density: Float,
        bgColor: Int,
        radiusDp: Float,
        strokeColor: Int = 0,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radiusDp * density
            if (strokeWidthDp > 0 && strokeColor != 0) {
                setStroke((strokeWidthDp * density).toInt(), strokeColor)
            }
        }
    }

    fun gradientBackground(
        density: Float,
        startColor: Int,
        endColor: Int,
        radiusDp: Float,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT,
        strokeColor: Int = 0,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable(orientation, intArrayOf(startColor, endColor)).apply {
            cornerRadius = radiusDp * density
            if (strokeWidthDp > 0 && strokeColor != 0) {
                setStroke((strokeWidthDp * density).toInt(), strokeColor)
            }
        }
    }

    fun withRipple(contentDrawable: Drawable, rippleColor: Int = Color.parseColor("#33FFFFFF")): Drawable {
        return RippleDrawable(ColorStateList.valueOf(rippleColor), contentDrawable, contentDrawable)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachSpringTouchFeedback(view: View, targetScale: Float = 0.95f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(targetScale).scaleY(targetScale).setDuration(90).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            false
        }
    }

    fun styledButton(
        context: Context,
        density: Float,
        label: String,
        bgColor: Int,
        textColor: Int = Color.WHITE,
        action: () -> Unit
    ): Button {
        val baseDrawable = roundedBackground(density, bgColor, 12f)
        return Button(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = withRipple(baseDrawable)
            minHeight = (48 * density).toInt()
            minWidth = (48 * density).toInt()
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            attachSpringTouchFeedback(this, 0.96f)
            setOnClickListener { action() }
        }
    }

    fun gradientButton(
        context: Context,
        density: Float,
        label: String,
        startColor: Int,
        endColor: Int,
        textColor: Int = Color.WHITE,
        action: () -> Unit
    ): Button {
        val baseDrawable = gradientBackground(density, startColor, endColor, 14f)
        return Button(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = withRipple(baseDrawable, Color.parseColor("#44FFFFFF"))
            elevation = 6f * density
            minHeight = (48 * density).toInt()
            minWidth = (48 * density).toInt()
            setPadding((16 * density).toInt(), (13 * density).toInt(), (16 * density).toInt(), (13 * density).toInt())
            attachSpringTouchFeedback(this, 0.97f)
            setOnClickListener { action() }
        }
    }

    fun chipBadge(context: Context, density: Float, text: String, bgColor: Int, textColor: Int): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBackground(density, bgColor, 8f)
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
        }
    }

    fun stepCard(
        context: Context,
        density: Float,
        stepNumber: String,
        stepTitle: String,
        description: String
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = roundedBackground(density, Color.parseColor(Colors.CARD_BG), 16f, Color.parseColor(Colors.CARD_BORDER), 1)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = TextView(context).apply {
                text = stepNumber
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = gradientBackground(
                    density,
                    Color.parseColor(Colors.BRAND_PRIMARY),
                    Color.parseColor(Colors.BRAND_SECONDARY),
                    12f
                )
                layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                    marginEnd = (10 * density).toInt()
                }
            }
            val titleView = TextView(context).apply {
                text = stepTitle
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            header.addView(badge)
            header.addView(titleView)
            addView(header)

            if (description.isNotEmpty()) {
                val descView = TextView(context).apply {
                    text = description
                    setTextColor(Color.parseColor(Colors.TEXT_MUTED))
                    textSize = 12f
                    setPadding((34 * density).toInt(), (3 * density).toInt(), 0, (8 * density).toInt())
                }
                addView(descView)
            }
        }
    }

    fun spacer(context: Context, density: Float, heightDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, (heightDp * density).toInt())
        }
    }

    fun spacerHoriz(context: Context, density: Float, widthDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams((widthDp * density).toInt(), 1)
        }
    }

    // Modern Avatar Generator with Initials & Deterministic Glowing Gradients
    fun avatarBadge(context: Context, density: Float, name: String, sizeDp: Int = 34): TextView {
        val palettes = listOf(
            Pair(Color.parseColor("#4F46E5"), Color.parseColor("#7C3AED")),
            Pair(Color.parseColor("#059669"), Color.parseColor("#10B981")),
            Pair(Color.parseColor("#0284C7"), Color.parseColor("#38BDF8")),
            Pair(Color.parseColor("#D97706"), Color.parseColor("#F59E0B")),
            Pair(Color.parseColor("#E11D48"), Color.parseColor("#FB7185")),
            Pair(Color.parseColor("#7C3AED"), Color.parseColor("#C084FC"))
        )
        val idx = if (name.isEmpty()) 0 else (name.hashCode().absoluteValue) % palettes.size
        val (start, end) = palettes[idx]
        val initial = if (name.isNotBlank()) name.trim().take(1).uppercase() else "👤"

        return TextView(context).apply {
            text = initial
            textSize = (sizeDp * 0.42f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = gradientBackground(density, start, end, sizeDp / 2f, strokeColor = Color.parseColor("#40FFFFFF"), strokeWidthDp = 1)
            layoutParams = LinearLayout.LayoutParams((sizeDp * density).toInt(), (sizeDp * density).toInt())
        }
    }

    // Modern Glowing Pill / Capsule with Leading Icon or Dot
    fun statusPill(
        context: Context,
        density: Float,
        text: String,
        dotColor: Int = Color.parseColor(Colors.ACCENT_EMERALD),
        bgColor: Int = Color.parseColor("#162032"),
        borderColor: Int = Color.parseColor("#26354D")
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(density, bgColor, 14f, borderColor, 1)
            setPadding((10 * density).toInt(), (5 * density).toInt(), (12 * density).toInt(), (5 * density).toInt())

            val dot = View(context).apply {
                background = roundedBackground(density, dotColor, 4f)
                layoutParams = LinearLayout.LayoutParams((7 * density).toInt(), (7 * density).toInt()).apply {
                    marginEnd = (6 * density).toInt()
                }
            }
            val label = TextView(context).apply {
                this.text = text
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(dot)
            addView(label)
        }
    }

    // Ultra-Modern Custom SeekBar Styling with High-Contrast Glowing Gradient Progress and Thumb
    fun styleCustomSeekBar(seekBar: SeekBar, density: Float) {
        seekBar.splitTrack = false

        // Background track (4dp sleek rail)
        val bgTrack = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = 3f * density
        }

        // Secondary Progress / Buffer track
        val secTrack = ClipDrawable(
            GradientDrawable().apply {
                setColor(Color.parseColor("#312E81"))
                cornerRadius = 3f * density
            },
            Gravity.START,
            ClipDrawable.HORIZONTAL
        )

        // Active Progress track (Vibrant Royal Indigo to Electric Violet gradient)
        val progressTrack = ClipDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#4F46E5"), Color.parseColor("#38BDF8"))
            ).apply {
                cornerRadius = 3f * density
            },
            Gravity.START,
            ClipDrawable.HORIZONTAL
        )

        val layers = LayerDrawable(arrayOf(bgTrack, secTrack, progressTrack))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.secondaryProgress)
        layers.setId(2, android.R.id.progress)

        // Inset by 10dp top & bottom to keep the visual rail slim while maintaining easy touch target
        val insetY = (10 * density).toInt()
        layers.setLayerInset(0, 0, insetY, 0, insetY)
        layers.setLayerInset(1, 0, insetY, 0, insetY)
        layers.setLayerInset(2, 0, insetY, 0, insetY)

        seekBar.progressDrawable = layers

        // Modern glowing thumb
        val thumbSize = (14 * density).toInt()
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(thumbSize, thumbSize)
            setStroke((2 * density).toInt(), Color.parseColor("#6366F1"))
        }
        seekBar.thumb = thumb
        seekBar.thumbOffset = (7 * density).toInt()
    }

    // Modern Circular Glass Button (Ideal for FABs, Video Controls, Mic, Chat)
    fun circularButton(
        context: Context,
        density: Float,
        iconText: String,
        bgColor: Int,
        sizeDp: Int = 48,
        borderColor: Int = Color.parseColor("#38BDF8"),
        contentDesc: String = "",
        action: () -> Unit
    ): TextView {
        val baseDrawable = roundedBackground(density, bgColor, sizeDp / 2f, borderColor, 1)
        return TextView(context).apply {
            text = iconText
            textSize = 18f
            gravity = Gravity.CENTER
            background = withRipple(baseDrawable)
            layoutParams = LinearLayout.LayoutParams((sizeDp * density).toInt(), (sizeDp * density).toInt())
            minHeight = (48 * density).toInt()
            minWidth = (48 * density).toInt()
            if (contentDesc.isNotEmpty()) contentDescription = contentDesc
            elevation = 12f
            isClickable = true
            isFocusable = true
            attachSpringTouchFeedback(this, 0.93f)
            setOnClickListener { action() }
        }
    }
}
