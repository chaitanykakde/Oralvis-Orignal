package com.oralvis.oralviscamera

import android.content.Context
import android.content.SharedPreferences

class ThemeManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_IS_DARK_THEME = "is_dark_theme"
        const val THEME_DARK = true
        const val THEME_LIGHT = false
    }
    
    var isDarkTheme: Boolean
        get() = prefs.getBoolean(KEY_IS_DARK_THEME, true) // Default is dark theme
        set(value) {
            prefs.edit().putBoolean(KEY_IS_DARK_THEME, value).apply()
        }
    
    fun toggleTheme(): Boolean {
        isDarkTheme = !isDarkTheme
        return isDarkTheme
    }
    
    fun getBackgroundColor(context: Context): Int {
        return if (isDarkTheme) {
            context.getColor(R.color.theme_background_dark)
        } else {
            context.getColor(R.color.theme_background_light)
        }
    }
    
    fun getSurfaceColor(context: Context): Int {
        return if (isDarkTheme) {
            context.getColor(R.color.theme_surface_dark)
        } else {
            context.getColor(R.color.theme_surface_light)
        }
    }
    
    fun getCardColor(context: Context): Int {
        return if (isDarkTheme) {
            context.getColor(R.color.theme_card_dark)
        } else {
            context.getColor(R.color.theme_card_light)
        }
    }
    
    fun getTextPrimaryColor(context: Context): Int {
        return if (isDarkTheme) {
            context.getColor(R.color.theme_text_primary_dark)
        } else {
            context.getColor(R.color.theme_text_primary_light)
        }
    }
    
    fun getTextSecondaryColor(context: Context): Int {
        return if (isDarkTheme) {
            context.getColor(R.color.theme_text_secondary_dark)
        } else {
            context.getColor(R.color.theme_text_secondary_light)
        }
    }
    
    fun getBorderColor(context: Context): Int {
        return if (isDarkTheme) {
            context.getColor(R.color.theme_border_dark)
        } else {
            context.getColor(R.color.theme_border_light)
        }
    }
}

