package com.watchtogether.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object UIUtils {
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

    fun roundedBackground(density: Float, bgColor: Int, radiusDp: Float, strokeColor: Int = 0, strokeWidthDp: Int = 0): GradientDrawable {
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

    fun styledButton(context: Context, density: Float, label: String, bgColor: Int, textColor: Int = Color.WHITE, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = roundedBackground(density, bgColor, 12f)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
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
        return Button(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = gradientBackground(density, startColor, endColor, 14f)
            elevation = 6f * density
            setPadding((16 * density).toInt(), (13 * density).toInt(), (16 * density).toInt(), (13 * density).toInt())
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

    fun stepCard(context: Context, density: Float, stepNumber: String, stepTitle: String, description: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = roundedBackground(density, Color.parseColor("#131B2A"), 14f, Color.parseColor("#223048"), 1)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, (5 * density).toInt(), 0, (5 * density).toInt())
            }
            val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val badge = TextView(context).apply {
                text = stepNumber
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = roundedBackground(density, Color.parseColor("#4F46E5"), 12f)
                layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
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
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12f
                    setPadding((32 * density).toInt(), (2 * density).toInt(), 0, (8 * density).toInt())
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
}
