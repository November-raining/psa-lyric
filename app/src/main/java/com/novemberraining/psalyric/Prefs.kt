package com.novemberraining.psalyric

import android.content.Context

object Prefs {
    private const val FILE = "psa_lyric"

    // User-tunable baseline values. The settings UI resets each slider to these
    // constants, so simulator/car tuning can change the product baseline later
    // without duplicating magic numbers in multiple screens.
    const val DEFAULT_FONT_SIZE = 29
    const val DEFAULT_FONT_WEIGHT = 600
    const val DEFAULT_LINE_SPACING = 0.9f
    const val DEFAULT_POSITION_PRESET = PsaPreset.PRESET_LOWER_RIGHT
    const val DEFAULT_X = 1373
    const val DEFAULT_Y = 249
    const val DEFAULT_WIDTH = 535
    const val DEFAULT_HEIGHT = 700
    const val DEFAULT_GLOW_INTENSITY = 260
    const val DEFAULT_GAUSSIAN_BLUR_STRENGTH = 200
    const val DEFAULT_GAUSSIAN_BLUR_DISTANCE_BOOST = 100
    const val DEFAULT_CARLIFE_ONLY_CARD = true
    private fun p(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()

    fun touchThrough(c: Context) = p(c).getBoolean("touch_through", false)
    fun setTouchThrough(c: Context, v: Boolean) = p(c).edit().putBoolean("touch_through", v).apply()

    fun launcherStrip(c: Context) = p(c).getBoolean("launcher_strip", true)
    fun setLauncherStrip(c: Context, v: Boolean) = p(c).edit().putBoolean("launcher_strip", v).apply()

    fun carLifeOnlyCard(c: Context) =
        p(c).getBoolean("carlife_only_card", DEFAULT_CARLIFE_ONLY_CARD)
    fun setCarLifeOnlyCard(c: Context, v: Boolean) =
        p(c).edit().putBoolean("carlife_only_card", v).apply()

    fun backgroundMode(c: Context) = p(c).getString("background_mode", "cover") ?: "cover"
    fun setBackgroundMode(c: Context, v: String) = p(c).edit().putString("background_mode", v).apply()

    fun screenCaptureEnabled(c: Context) = p(c).getBoolean("screen_capture", false)
    fun setScreenCaptureEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("screen_capture", v).apply()

    fun fontSize(c: Context) = p(c).getInt("font_size", DEFAULT_FONT_SIZE).coerceIn(12, 50)
    fun setFontSize(c: Context, v: Int) = p(c).edit().putInt("font_size", v.coerceIn(12, 50)).apply()

    fun fontWeight(c: Context) = p(c).getInt("font_weight", DEFAULT_FONT_WEIGHT).coerceIn(300, 900)
    fun setFontWeight(c: Context, v: Int) = p(c).edit().putInt("font_weight", v.coerceIn(300, 900)).apply()

    fun lineSpacing(c: Context) = p(c).getFloat("line_spacing", DEFAULT_LINE_SPACING).coerceIn(0.5f, 2f)
    fun setLineSpacing(c: Context, v: Float) = p(c).edit().putFloat("line_spacing", v.coerceIn(0.5f, 2f)).apply()

    fun gaussianBlur(c: Context) = p(c).getBoolean("gaussian_blur", true)
    fun setGaussianBlur(c: Context, v: Boolean) = p(c).edit().putBoolean("gaussian_blur", v).apply()

    fun gaussianBlurStrength(c: Context) =
        p(c).getInt(
            "gaussian_blur_strength",
            DEFAULT_GAUSSIAN_BLUR_STRENGTH
        ).coerceIn(50, 300)
    fun setGaussianBlurStrength(c: Context, v: Int) =
        p(c).edit()
            .putInt("gaussian_blur_strength", v.coerceIn(50, 300))
            .apply()

    fun gaussianBlurDistanceBoost(c: Context) =
        p(c).getInt(
            "gaussian_blur_distance_boost",
            DEFAULT_GAUSSIAN_BLUR_DISTANCE_BOOST
        ).coerceIn(0, 250)
    fun setGaussianBlurDistanceBoost(c: Context, v: Int) =
        p(c).edit()
            .putInt("gaussian_blur_distance_boost", v.coerceIn(0, 250))
            .apply()

    fun glow(c: Context) = p(c).getBoolean("glow", true)
    fun setGlow(c: Context, v: Boolean) = p(c).edit().putBoolean("glow", v).apply()

    fun glowIntensity(c: Context) =
        p(c).getInt("glow_intensity", DEFAULT_GLOW_INTENSITY).coerceIn(80, 400)
    fun setGlowIntensity(c: Context, v: Int) =
        p(c).edit().putInt("glow_intensity", v.coerceIn(80, 400)).apply()

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

    private fun ensurePositionPreset(c: Context) {
        val prefs = p(c)
        if (prefs.contains("position_preset")) return

        // 0.1.20 changes the product default to the lower-right CarLife card.
        // Existing installs did not have a preset key, so migrate them once to
        // the new default instead of silently retaining the old y=10 geometry.
        val g = PsaPreset.geometryForPreset(DEFAULT_POSITION_PRESET)
        prefs.edit()
            .putString("position_preset", DEFAULT_POSITION_PRESET)
            .putInt("x", g.x)
            .putInt("y", g.y)
            .putInt("width", g.width)
            .putInt("height", g.height)
            .apply()
    }

    fun positionPreset(c: Context): String {
        ensurePositionPreset(c)
        return PsaPreset.normalizePreset(p(c).getString("position_preset", DEFAULT_POSITION_PRESET))
    }

    fun x(c: Context): Int {
        ensurePositionPreset(c)
        return p(c).getInt("x", DEFAULT_X)
    }

    fun y(c: Context): Int {
        ensurePositionPreset(c)
        return p(c).getInt("y", DEFAULT_Y)
    }

    fun width(c: Context): Int {
        ensurePositionPreset(c)
        return p(c).getInt("width", DEFAULT_WIDTH).coerceAtLeast(260)
    }

    fun height(c: Context): Int {
        ensurePositionPreset(c)
        return p(c).getInt("height", DEFAULT_HEIGHT).coerceAtLeast(300)
    }

    fun setPositionPreset(c: Context, preset: String) {
        val normalized = PsaPreset.normalizePreset(preset)
        val g = PsaPreset.geometryForPreset(normalized)
        p(c).edit()
            .putString("position_preset", normalized)
            .putInt("x", g.x)
            .putInt("y", g.y)
            .putInt("width", g.width)
            .putInt("height", g.height)
            .apply()
    }

    fun setGeometry(c: Context, x: Int, y: Int, width: Int, height: Int) {
        p(c).edit()
            .putString("position_preset", PsaPreset.PRESET_CUSTOM)
            .putInt("x", x).putInt("y", y)
            .putInt("width", width.coerceAtLeast(260))
            .putInt("height", height.coerceAtLeast(300)).apply()
    }

    fun resetGeometry(c: Context) =
        setPositionPreset(c, DEFAULT_POSITION_PRESET)
}
