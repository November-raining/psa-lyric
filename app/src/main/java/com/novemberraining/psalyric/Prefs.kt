package com.novemberraining.psalyric

import android.content.Context

object Prefs {
    private const val FILE = "psa_lyric"
    private fun p(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()

    fun touchThrough(c: Context) = p(c).getBoolean("touch_through", false)
    fun setTouchThrough(c: Context, v: Boolean) = p(c).edit().putBoolean("touch_through", v).apply()

    fun launcherStrip(c: Context) = p(c).getBoolean("launcher_strip", true)
    fun setLauncherStrip(c: Context, v: Boolean) = p(c).edit().putBoolean("launcher_strip", v).apply()

    fun backgroundMode(c: Context) = p(c).getString("background_mode", "cover") ?: "cover"
    fun setBackgroundMode(c: Context, v: String) = p(c).edit().putString("background_mode", v).apply()

    fun screenCaptureEnabled(c: Context) = p(c).getBoolean("screen_capture", false)
    fun setScreenCaptureEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("screen_capture", v).apply()

    fun fontSize(c: Context) = p(c).getInt("font_size", 29).coerceIn(12, 50)
    fun setFontSize(c: Context, v: Int) = p(c).edit().putInt("font_size", v.coerceIn(12, 50)).apply()

    fun fontWeight(c: Context) = p(c).getInt("font_weight", 600).coerceIn(300, 900)
    fun setFontWeight(c: Context, v: Int) = p(c).edit().putInt("font_weight", v.coerceIn(300, 900)).apply()

    fun lineSpacing(c: Context) = p(c).getFloat("line_spacing", 0.9f).coerceIn(0.5f, 2f)
    fun setLineSpacing(c: Context, v: Float) = p(c).edit().putFloat("line_spacing", v.coerceIn(0.5f, 2f)).apply()

    fun gaussianBlur(c: Context) = p(c).getBoolean("gaussian_blur", true)
    fun setGaussianBlur(c: Context, v: Boolean) = p(c).edit().putBoolean("gaussian_blur", v).apply()

    fun glow(c: Context) = p(c).getBoolean("glow", true)
    fun setGlow(c: Context, v: Boolean) = p(c).edit().putBoolean("glow", v).apply()

    fun dynamicColor(c: Context) = p(c).getBoolean("dynamic_color", true)
    fun setDynamicColor(c: Context, v: Boolean) = p(c).edit().putBoolean("dynamic_color", v).apply()

    fun duet(c: Context) = p(c).getBoolean("duet", true)
    fun setDuet(c: Context, v: Boolean) = p(c).edit().putBoolean("duet", v).apply()

    fun balancedWrap(c: Context) = p(c).getBoolean("balanced_wrap", true)
    fun setBalancedWrap(c: Context, v: Boolean) = p(c).edit().putBoolean("balanced_wrap", v).apply()

    fun lyricOffsetMs(c: Context) = p(c).getLong("lyric_offset_ms", 0L)
    fun setLyricOffsetMs(c: Context, v: Long) = p(c).edit().putLong("lyric_offset_ms", v.coerceIn(-5000L, 5000L)).apply()

    fun secondaryLyricEnabled(c: Context) = p(c).getBoolean("secondary_lyric_enabled", true)
    fun setSecondaryLyricEnabled(c: Context, v: Boolean) =
        p(c).edit().putBoolean("secondary_lyric_enabled", v).apply()

    fun secondaryLyricMode(c: Context) = LyricSecondaryRules.normalizeMode(
        p(c).getString("secondary_lyric_mode", LyricSecondaryRules.MODE_TRANSLATION)
    )
    fun setSecondaryLyricMode(c: Context, v: String) =
        p(c).edit()
            .putString("secondary_lyric_mode", LyricSecondaryRules.normalizeMode(v))
            .apply()

    fun x(c: Context) = p(c).getInt("x", 1373)
    fun y(c: Context) = p(c).getInt("y", 10)
    fun width(c: Context) = p(c).getInt("width", 535).coerceAtLeast(260)
    fun height(c: Context) = p(c).getInt("height", 700).coerceAtLeast(300)

    fun setGeometry(c: Context, x: Int, y: Int, width: Int, height: Int) {
        p(c).edit()
            .putInt("x", x).putInt("y", y)
            .putInt("width", width.coerceAtLeast(260))
            .putInt("height", height.coerceAtLeast(300)).apply()
    }

    fun resetGeometry(c: Context) = setGeometry(c, 1373, 10, 535, 700)
}
