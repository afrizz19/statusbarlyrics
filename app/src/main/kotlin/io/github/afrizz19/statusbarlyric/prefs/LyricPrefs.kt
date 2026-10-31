package io.github.afrizz19.statusbarlyric.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings shared between the config UI (this app's process) and the hook (SystemUI's
 * process). LSPosed bridges them: `xposedsharedprefs` in the manifest makes this app's
 * preference file world-readable, so the hook can read it with `XSharedPreferences`.
 */
object LyricPrefs {

    const val FILE = "lyric_settings"

    /** ARGB colour of the lyric text, or [FOLLOW_CLOCK]. */
    const val KEY_TEXT_COLOR = "text_color"

    const val KEY_SHADOW_ON = "shadow_on"
    const val KEY_SHADOW_COLOR = "shadow_color"
    const val KEY_SHADOW_RADIUS = "shadow_radius_dp"
    const val KEY_SHADOW_DX = "shadow_dx_dp"
    const val KEY_SHADOW_DY = "shadow_dy_dp"

    const val KEY_OUTLINE_ON = "outline_on"
    const val KEY_OUTLINE_COLOR = "outline_color"
    const val KEY_OUTLINE_WIDTH = "outline_width_dp"

    /** Milliseconds added to the playback position. Positive = show lines earlier. */
    const val KEY_SYNC_OFFSET = "sync_offset_ms"

    /** [MODE_LINE] or [MODE_WORD]. */
    const val KEY_LYRIC_MODE = "lyric_mode"

    /** ARGB colour of the not-yet-sung part in word mode, or [DERIVE_DIM]. */
    const val KEY_WORD_DIM_COLOR = "word_dim_color"

    /** Alpha applied to the lyric colour when the dim colour is [DERIVE_DIM]. */
    const val KEY_WORD_DIM_ALPHA = "word_dim_alpha"

    /** Interpolate the highlight between ticks instead of stepping at the tick rate. */
    const val KEY_WORD_SMOOTH = "word_smooth"

    /** Sentinel: use whatever colour the status bar clock currently has. */
    const val FOLLOW_CLOCK = 0

    /** Sentinel: derive the unsung colour from the lyric colour at [KEY_WORD_DIM_ALPHA]. */
    const val DERIVE_DIM = 0

    /** Whole lines, exactly as before. */
    const val MODE_LINE = 0

    /** Per-word karaoke highlight, sourced from lrcmux. */
    const val MODE_WORD = 1

    private const val BLACK = 0xFF000000.toInt()

    const val DEF_SHADOW_RADIUS = 2f
    const val DEF_SHADOW_DX = 0f
    const val DEF_SHADOW_DY = 1f
    const val DEF_OUTLINE_WIDTH = 1f
    const val DEF_SYNC_OFFSET = 0
    const val DEF_WORD_DIM_ALPHA = 0.45f

    /** Range offered by the offset slider, in ms. */
    const val SYNC_OFFSET_MIN = -1500
    const val SYNC_OFFSET_MAX = 1500

    /** Swatches offered in the config UI. */
    val PRESETS = listOf(
        0xFFFFFFFF.toInt(), // white
        0xFF000000.toInt(), // black
        0xFFFF5252.toInt(), // red
        0xFFFF9800.toInt(), // orange
        0xFFFFEB3B.toInt(), // yellow
        0xFF4CAF50.toInt(), // green
        0xFF00E5FF.toInt(), // cyan
        0xFF448AFF.toInt(), // blue
        0xFFB388FF.toInt(), // purple
        0xFFFF80AB.toInt(), // pink
    )

    /**
     * True when [of] got a world-readable file, i.e. LSPosed is active for this module
     * and SystemUI can read the settings. False means changes stay inside the app.
     */
    @Volatile
    var sharedWithHook: Boolean = false
        private set

    /**
     * The preference file must be world-readable or SystemUI can't see it. LSPosed
     * intercepts `MODE_WORLD_READABLE` for module apps; if it doesn't (module not
     * enabled yet), fall back so the UI still works — the hook just keeps its default.
     */
    @Suppress("DEPRECATION")
    fun of(context: Context): SharedPreferences =
        (runCatching { context.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE) }
            .onSuccess { sharedWithHook = true }
            .onFailure { sharedWithHook = false }
            .getOrElse { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) })
            .also {
                // The file isn't written to disk until the first edit, and until it
                // exists the hook has nothing to read. Seed it on first launch.
                if (!it.contains(KEY_TEXT_COLOR)) setTextColor(it, FOLLOW_CLOCK)
            }

    fun textColor(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_TEXT_COLOR, FOLLOW_CLOCK)

    fun setTextColor(prefs: SharedPreferences, color: Int) {
        prefs.edit().putInt(KEY_TEXT_COLOR, color).apply()
    }

    /**
     * Everything that affects how the lyric is painted, read in one go. Distances are
     * in dp — the hook converts them using the status bar's own density.
     *
     * [textColor] is the **sung** colour in word mode, so a track with no word data
     * (drawn entirely sung) looks exactly like line mode does today.
     */
    data class Style(
        val textColor: Int,
        val shadowOn: Boolean,
        val shadowColor: Int,
        val shadowRadiusDp: Float,
        val shadowDxDp: Float,
        val shadowDyDp: Float,
        val outlineOn: Boolean,
        val outlineColor: Int,
        val outlineWidthDp: Float,
        val syncOffsetMs: Int,
        val mode: Int,
        val wordDimColor: Int,
        val wordDimAlpha: Float,
        val wordSmooth: Boolean,
    ) {
        companion object {
            val DEFAULT = Style(
                textColor = FOLLOW_CLOCK,
                shadowOn = false,
                shadowColor = BLACK,
                shadowRadiusDp = DEF_SHADOW_RADIUS,
                shadowDxDp = DEF_SHADOW_DX,
                shadowDyDp = DEF_SHADOW_DY,
                outlineOn = false,
                outlineColor = BLACK,
                outlineWidthDp = DEF_OUTLINE_WIDTH,
                syncOffsetMs = DEF_SYNC_OFFSET,
                mode = MODE_LINE,
                wordDimColor = DERIVE_DIM,
                wordDimAlpha = DEF_WORD_DIM_ALPHA,
                wordSmooth = false,
            )
        }
    }

    /**
     * Resolves the unsung colour for a given sung colour: the explicit setting when there
     * is one, else the sung colour faded to [Style.wordDimAlpha].
     */
    fun dimColorFor(style: Style, sungColor: Int): Int {
        if (style.wordDimColor != DERIVE_DIM) return style.wordDimColor
        val alpha = (((sungColor ushr 24) and 0xFF) * style.wordDimAlpha)
            .toInt().coerceIn(0, 255)
        return (alpha shl 24) or (sungColor and 0x00FFFFFF)
    }

    fun style(prefs: SharedPreferences): Style = Style(
        textColor = prefs.getInt(KEY_TEXT_COLOR, FOLLOW_CLOCK),
        shadowOn = prefs.getBoolean(KEY_SHADOW_ON, false),
        shadowColor = prefs.getInt(KEY_SHADOW_COLOR, BLACK),
        shadowRadiusDp = prefs.getFloat(KEY_SHADOW_RADIUS, DEF_SHADOW_RADIUS),
        shadowDxDp = prefs.getFloat(KEY_SHADOW_DX, DEF_SHADOW_DX),
        shadowDyDp = prefs.getFloat(KEY_SHADOW_DY, DEF_SHADOW_DY),
        outlineOn = prefs.getBoolean(KEY_OUTLINE_ON, false),
        outlineColor = prefs.getInt(KEY_OUTLINE_COLOR, BLACK),
        outlineWidthDp = prefs.getFloat(KEY_OUTLINE_WIDTH, DEF_OUTLINE_WIDTH),
        syncOffsetMs = prefs.getInt(KEY_SYNC_OFFSET, DEF_SYNC_OFFSET),
        mode = prefs.getInt(KEY_LYRIC_MODE, MODE_LINE),
        wordDimColor = prefs.getInt(KEY_WORD_DIM_COLOR, DERIVE_DIM),
        wordDimAlpha = prefs.getFloat(KEY_WORD_DIM_ALPHA, DEF_WORD_DIM_ALPHA),
        wordSmooth = prefs.getBoolean(KEY_WORD_SMOOTH, false),
    )

    fun save(prefs: SharedPreferences, style: Style) {
        prefs.edit()
            .putInt(KEY_TEXT_COLOR, style.textColor)
            .putBoolean(KEY_SHADOW_ON, style.shadowOn)
            .putInt(KEY_SHADOW_COLOR, style.shadowColor)
            .putFloat(KEY_SHADOW_RADIUS, style.shadowRadiusDp)
            .putFloat(KEY_SHADOW_DX, style.shadowDxDp)
            .putFloat(KEY_SHADOW_DY, style.shadowDyDp)
            .putBoolean(KEY_OUTLINE_ON, style.outlineOn)
            .putInt(KEY_OUTLINE_COLOR, style.outlineColor)
            .putFloat(KEY_OUTLINE_WIDTH, style.outlineWidthDp)
            .putInt(KEY_SYNC_OFFSET, style.syncOffsetMs)
            .putInt(KEY_LYRIC_MODE, style.mode)
            .putInt(KEY_WORD_DIM_COLOR, style.wordDimColor)
            .putFloat(KEY_WORD_DIM_ALPHA, style.wordDimAlpha)
            .putBoolean(KEY_WORD_SMOOTH, style.wordSmooth)
            .apply()
    }

    /** Parses `#RGB`, `#RRGGBB` or `#AARRGGBB`; null if it isn't a colour. */
    fun parseHex(input: String): Int? {
        val hex = input.trim().removePrefix("#")
        val expanded = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
        val value = expanded.toLongOrNull(16) ?: return null
        return value.toInt()
    }

    fun toHex(color: Int): String = String.format("#%08X", color)
}
