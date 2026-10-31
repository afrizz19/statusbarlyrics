package io.github.afrizz19.statusbarlyric.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import io.github.afrizz19.statusbarlyric.api.LyricsRepository
import io.github.afrizz19.statusbarlyric.media.MediaSessionReader
import io.github.afrizz19.statusbarlyric.media.TrackInfo
import io.github.afrizz19.statusbarlyric.prefs.LyricPrefs
import io.github.afrizz19.statusbarlyric.sync.ActiveLine
import io.github.afrizz19.statusbarlyric.sync.LyricSyncEngine
import io.github.afrizz19.statusbarlyric.sync.WordProgressResolver
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Drives the lyric view(s) from inside the SystemUI process:
 *
 *   poll media session -> on track change fetch lyrics -> resolve active line -> render
 *
 * The tick runs on SystemUI's main thread (view updates must), and network work is
 * handed to a single background thread.
 */
object LyricController {

    private const val TAG = "StatusBarLyric16"

    /**
     * How often the active line is re-evaluated. Kept short because the whole error
     * budget of a line change is one tick — the position itself is extrapolated, so
     * ticking faster costs nothing but a comparison.
     */
    private const val TICK_MS = 100L

    /** How often the media session is actually polled (a binder call, so not every tick). */
    private const val POLL_MS = 500L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lyric-fetch") }

    private val repo = LyricsRepository()
    private var reader: MediaSessionReader? = null

    /**
     * SystemUI has several views with id `clock` (status bar, keyguard, ...). We inject
     * into each one and drive them all, because only the currently visible status bar
     * will actually be on screen — driving just one is a coin flip.
     */
    private class Slot(lyric: LyricView, clock: TextView) {
        val lyric = WeakReference(lyric)
        val clock = WeakReference(clock)
        fun alive() = lyric.get() != null
    }

    private val slots = mutableListOf<Slot>()

    private var currentTrackKey: String? = null
    private var engine: LyricSyncEngine? = null
    /** Guards against a slow fetch for an old track overwriting a newer one. */
    private var fetchToken = 0
    private var shownText: String? = null
    // Removed: mode switching is gone, lrcmux is the only provider

    private var running = false

    /** Last cached track, so the tick can re-evaluate the line without polling the session. */
    private var cachedTrack: TrackInfo? = null
    private var lastPollMs = 0L

    fun start(context: Context, lyricView: LyricView, clock: TextView) {
        slots.removeAll { !it.alive() }
        slots.add(Slot(lyricView, clock))
        if (reader == null) reader = MediaSessionReader(context)

        // A freshly injected view starts hidden; re-apply whatever we're showing.
        shownText?.let { applyToSlots(it) }

        if (running) return
        running = true
        main.post(tick)
        XposedBridge.log("$TAG: controller started (${slots.size} slot(s))")
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                update()
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: tick failed: ${android.util.Log.getStackTraceString(t)}")
            }
            slots.removeAll { !it.alive() }
            // Stop ticking if every view was torn down (SystemUI restart) — the hook
            // will call start() again on the new views.
            if (slots.isEmpty()) {
                running = false
                XposedBridge.log("$TAG: controller stopped (no views)")
                return
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun update() {
        val now = SystemClock.elapsedRealtime()
        val style = applyStyle()

        // Polling the session is a binder round-trip; between polls the cached snapshot
        // extrapolates its own position, which is what actually drives the line change.
        val track = if (now - lastPollMs >= POLL_MS) {
            lastPollMs = now
            reader?.readActiveTrack().also { cachedTrack = it }
        } else {
            cachedTrack
        }

        if (track == null || !track.isPlaying) {
            clearTrack()
            render(null)
            return
        }

        // Refetch on track change.
        if (track.trackKey != currentTrackKey) onTrackChanged(track, style)

        val currentEngine = engine ?: return
        val pos = track.estimatedPositionMs(now) + style.syncOffsetMs
        val active = currentEngine.activeLineAt(pos)
        val text = active?.text?.takeIf { it.isNotBlank() }
        render(text)
        pushWordProgress(style, active, pos)
    }

    /**
     * Pushes the karaoke sweep to the views. Called **after** [render] so it never touches
     * the [shownText] dedup and never retriggers the line-swap fade.
     *
     * No-ops in line mode, and for a track whose provider had no word data — that line then
     * keeps drawing exactly as it does in line mode, which is the chosen fallback.
     */
    private fun pushWordProgress(style: LyricPrefs.Style, active: ActiveLine?, pos: Long) {
        val words = active?.words
        if (style.mode != LyricPrefs.MODE_WORD || words.isNullOrEmpty()) {
            for (slot in slots) slot.lyric.get()?.clearWordProgress()
            return
        }

        val offset = WordProgressResolver.charOffsetAt(words, pos)
        for (slot in slots) {
            val lyric = slot.lyric.get() ?: continue
            val sung = if (style.textColor == LyricPrefs.FOLLOW_CLOCK) {
                slot.clock.get()?.currentTextColor ?: continue
            } else {
                style.textColor
            }
            lyric.setWordProgress(
                offset = offset,
                forText = active.text,
                sungColor = sung,
                unsungColor = LyricPrefs.dimColorFor(style, sung),
                smooth = style.wordSmooth,
                words = words,
                positionMs = pos,
            )
        }
    }

    private fun onTrackChanged(track: TrackInfo, @Suppress("UNUSED_PARAMETER") style: LyricPrefs.Style) {
        currentTrackKey = track.trackKey
        engine = null
        render(null)

        val token = ++fetchToken
        val artist = track.artist
        val title = track.title
        val album = track.album
        val durationMs = track.durationMs

        io.execute {
            val result = repo.fetchLyrics(artist, title, album, durationMs)
            main.post {
                // A newer track was selected while this fetch was in flight — drop it.
                if (token != fetchToken) return@post
                if (result == null || result.lines.isEmpty()) {
                    XposedBridge.log("$TAG: no lyrics for $artist - $title")
                    return@post
                }
                engine = LyricSyncEngine(result.lines)
                XposedBridge.log(
                    "$TAG: loaded ${result.lines.size} lines for $artist - $title " +
                        "(level=${result.level} source=${result.source ?: "?"})"
                )
            }
        }
    }

    private fun clearTrack() {
        currentTrackKey = null
        engine = null
        cachedTrack = null
        fetchToken++
    }

    /**
     * Pushes the configured style to every view. Done on the tick rather than once at
     * injection so a change in the config app shows up without restarting SystemUI —
     * and so "follow clock" keeps tracking the clock across light/dark status bars.
     *
     * @return the style, which also carries the sync offset used by [update].
     */
    private fun applyStyle(): LyricPrefs.Style {
        val style = HookPrefs.style(SystemClock.uptimeMillis())
        for (slot in slots) {
            val lyric = slot.lyric.get() ?: continue
            val color = if (style.textColor == LyricPrefs.FOLLOW_CLOCK) {
                slot.clock.get()?.currentTextColor ?: continue
            } else {
                style.textColor
            }
            lyric.applyStyle(style, color)
        }
        return style
    }

    /** Shows [text] in place of the clock, or restores the clock when null. */
    private fun render(text: String?) {
        if (text == shownText) return
        val first = shownText == null && text != null
        shownText = text
        applyToSlots(text)
        if (first) XposedBridge.log("$TAG: rendering to ${slots.size} slot(s)")
    }

    private fun applyToSlots(text: String?) {
        for (slot in slots) {
            val lyric = slot.lyric.get() ?: continue
            val clock = slot.clock.get()

            if (text == null) {
                // Restore the clock only once the lyric has finished fading out.
                lyric.hideLyric { clock?.visibility = View.VISIBLE }
                continue
            }

            clock?.visibility = View.GONE
            // The view owns the transition (fade + slide) and the 1-row/2-row choice.
            lyric.setLyric(text)
        }
    }
}
