package com.fran.teclas

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build

internal object DefaultKeyboardGlass {
    fun isGalaxyDevice(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
            Build.BRAND.equals("samsung", ignoreCase = true)

    fun deck(tokens: NeuTokens, radiusPx: Float, galaxy: Boolean = isGalaxyDevice()): Drawable {
        val light = tokens.mode == NeuMode.LIGHT
        val colors = when {
            galaxy && light -> intArrayOf(
                adjustAlpha(Color.WHITE, 0.20f),
                adjustAlpha(tokens.baseHi, 0.12f),
                adjustAlpha(tokens.baseLo, 0.10f)
            )
            galaxy -> intArrayOf(
                adjustAlpha(tokens.baseHi, 0.18f),
                adjustAlpha(tokens.base, 0.10f),
                adjustAlpha(tokens.baseLo, 0.22f)
            )
            light -> intArrayOf(
                adjustAlpha(Color.WHITE, 0.28f),
                adjustAlpha(tokens.baseHi, 0.18f),
                adjustAlpha(tokens.baseLo, 0.16f)
            )
            else -> intArrayOf(
                adjustAlpha(tokens.baseHi, 0.28f),
                adjustAlpha(tokens.base, 0.20f),
                adjustAlpha(tokens.baseLo, 0.32f)
            )
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = radiusPx
            val strokePx = (radiusPx / 18f).toInt().coerceIn(1, 3)
            setStroke(
                strokePx,
                adjustAlpha(
                    Color.WHITE,
                    when {
                        galaxy && light -> 0.46f
                        galaxy -> 0.18f
                        light -> 0.40f
                        else -> 0.14f
                    }
                )
            )
        }
    }
}
