package com.borealroutes.app

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color

object RouteColours {
    private val fallbackPalette = listOf(
        "#1479D1", "#6D55D9", "#008C61", "#D35D24", "#B43B72",
        "#1B8EA5", "#6A8A24", "#A449A1", "#BA7A12", "#496DB7"
    )

    fun hex(leg: Leg): String {
        normalize(leg.routeColor)?.let { return it }
        val agency = leg.agencyName.lowercase()
        return when {
            agency.contains("arriva") -> "#1479D1"
            agency.contains("stagecoach") -> "#1B75BB"
            agency.contains("first") -> "#6B3FA0"
            agency.contains("go-ahead") || agency.contains("metrobus") -> "#D52776"
            agency.contains("uno") -> "#7A2C91"
            agency.contains("centrebus") -> "#00854A"
            agency.contains("diamond") -> "#E21F2F"
            agency.contains("trent") || agency.contains("barton") -> "#E67D21"
            agency.contains("national express") -> "#BE1E2D"
            else -> fallbackPalette[(leg.serviceLabel.hashCode().and(Int.MAX_VALUE)) % fallbackPalette.size]
        }
    }

    fun compose(leg: Leg): Color = Color(android(leg))
    fun android(leg: Leg): Int = runCatching { AndroidColor.parseColor(hex(leg)) }.getOrDefault(AndroidColor.rgb(20, 121, 209))

    fun textCompose(leg: Leg): Color {
        normalize(leg.routeTextColor)?.let {
            return Color(runCatching { AndroidColor.parseColor(it) }.getOrDefault(AndroidColor.WHITE))
        }
        return if (isLight(android(leg))) Color.Black else Color.White
    }

    private fun normalize(raw: String?): String? {
        val s = raw?.trim()?.removePrefix("#") ?: return null
        if (!Regex("^[0-9A-Fa-f]{6}$").matches(s)) return null
        return "#${s.uppercase()}"
    }

    private fun isLight(color: Int): Boolean {
        val r = AndroidColor.red(color)
        val g = AndroidColor.green(color)
        val b = AndroidColor.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b) > 180
    }
}
