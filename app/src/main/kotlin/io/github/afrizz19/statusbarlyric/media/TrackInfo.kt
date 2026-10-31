package io.github.afrizz19.statusbarlyric.media

/** Snapshot of what a music app is currently playing. */
data class TrackInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    /** SystemClock.elapsedRealtime() when positionMs was sampled. */
    val positionSampledAt: Long,
    /** PlaybackState.speed, 1.0 for normal playback. */
    val speed: Float = 1f,
) {
    /** Stable identity of a track, independent of playback position. */
    val trackKey: String get() = "$packageName|$artist|$title"

    /** Estimated current position, extrapolated from the last sample if playing. */
    fun estimatedPositionMs(nowElapsed: Long): Long {
        if (!isPlaying) return positionMs
        val delta = (nowElapsed - positionSampledAt).coerceAtLeast(0)
        // Apps that support speed control report it here; at 1.25x the position runs
        // 25% faster than wall clock and a flat extrapolation falls steadily behind.
        val rate = if (speed > 0f) speed else 1f
        val est = positionMs + (delta * rate).toLong()
        return if (durationMs > 0) est.coerceAtMost(durationMs) else est
    }

    companion object {
        const val PKG_SPOTIFY = "com.spotify.music"
        const val PKG_YT_MUSIC = "com.google.android.apps.youtube.music"
        const val PKG_YT_MUSIC_REVANCED = "app.revanced.android.apps.youtube.music"

        val SUPPORTED_PACKAGES = setOf(PKG_SPOTIFY, PKG_YT_MUSIC, PKG_YT_MUSIC_REVANCED)
    }
}
