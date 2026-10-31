package io.github.afrizz19.statusbarlyric.xposed

import io.github.afrizz19.statusbarlyric.BuildConfig
import io.github.afrizz19.statusbarlyric.prefs.LyricPrefs
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

/**
 * Reads the config app's settings from inside SystemUI.
 *
 * The file is only re-read when it has actually changed, and at most every
 * [RELOAD_MS], so this is cheap enough to poll from the render tick — which is what
 * makes colour changes apply live, without restarting SystemUI.
 */
object HookPrefs {

    private const val TAG = "StatusBarLyric16"
    private const val RELOAD_MS = 1000L

    private var prefs: XSharedPreferences? = null
    private var lastReload = 0L
    private var warned = false

    private fun current(nowMs: Long): XSharedPreferences? {
        val existing = prefs ?: runCatching {
            XSharedPreferences(BuildConfig.APPLICATION_ID, LyricPrefs.FILE)
        }.getOrNull()?.also { prefs = it }

        if (existing == null) return null

        if (!existing.file.canRead()) {
            if (!warned) {
                warned = true
                XposedBridge.log(
                    "$TAG: settings not readable (${existing.file}); using defaults. " +
                        "Open the app once with the module enabled."
                )
            }
            return null
        }

        if (nowMs - lastReload >= RELOAD_MS) {
            lastReload = nowMs
            runCatching { existing.reload() }
        }
        return existing
    }

    /** Everything that affects painting, or defaults if the file isn't readable yet. */
    fun style(nowMs: Long): LyricPrefs.Style {
        val prefs = current(nowMs) ?: return LyricPrefs.Style.DEFAULT
        return LyricPrefs.Style(
            textColor = prefs.getInt(LyricPrefs.KEY_TEXT_COLOR, LyricPrefs.FOLLOW_CLOCK),
            shadowOn = prefs.getBoolean(LyricPrefs.KEY_SHADOW_ON, false),
            shadowColor = prefs.getInt(LyricPrefs.KEY_SHADOW_COLOR, BLACK),
            shadowRadiusDp = prefs.getFloat(LyricPrefs.KEY_SHADOW_RADIUS, LyricPrefs.DEF_SHADOW_RADIUS),
            shadowDxDp = prefs.getFloat(LyricPrefs.KEY_SHADOW_DX, LyricPrefs.DEF_SHADOW_DX),
            shadowDyDp = prefs.getFloat(LyricPrefs.KEY_SHADOW_DY, LyricPrefs.DEF_SHADOW_DY),
            outlineOn = prefs.getBoolean(LyricPrefs.KEY_OUTLINE_ON, false),
            outlineColor = prefs.getInt(LyricPrefs.KEY_OUTLINE_COLOR, BLACK),
            outlineWidthDp = prefs.getFloat(LyricPrefs.KEY_OUTLINE_WIDTH, LyricPrefs.DEF_OUTLINE_WIDTH),
            syncOffsetMs = prefs.getInt(LyricPrefs.KEY_SYNC_OFFSET, LyricPrefs.DEF_SYNC_OFFSET),
            mode = prefs.getInt(LyricPrefs.KEY_LYRIC_MODE, LyricPrefs.MODE_LINE),
            wordDimColor = prefs.getInt(LyricPrefs.KEY_WORD_DIM_COLOR, LyricPrefs.DERIVE_DIM),
            wordDimAlpha = prefs.getFloat(LyricPrefs.KEY_WORD_DIM_ALPHA, LyricPrefs.DEF_WORD_DIM_ALPHA),
            wordSmooth = prefs.getBoolean(LyricPrefs.KEY_WORD_SMOOTH, false),
        )
    }

    private val BLACK = 0xFF000000.toInt()
}
