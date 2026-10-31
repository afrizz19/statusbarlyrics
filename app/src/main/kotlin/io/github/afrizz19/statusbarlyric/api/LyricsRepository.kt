package io.github.afrizz19.statusbarlyric.api

/**
 * Fetches synced lyrics from lrcmux, which provides both line-level and word-level
 * (karaoke) timing when available.
 */
class LyricsRepository(
    private val client: LrcMuxApiClient = LrcMuxApiClient(),
) {

    /**
     * @param durationMs track length in milliseconds, 0 if unknown. Improves lrcmux matching.
     * @return the result, or null on any failure (network, non-200, empty, parse).
     */
    fun fetchLyrics(
        artist: String,
        title: String,
        album: String = "",
        durationMs: Long = 0L,
    ): LyricsResult? {
        val result = client.fetchLyrics(artist, title, album, durationMs) ?: return null

        // Applied here rather than in the client: a bilingual result is a property of the
        // lyric, not of the provider.
        val stripped = TranslationFilter.strip(result.lines)
        return if (stripped.size == result.lines.size) result else result.copy(lines = stripped)
    }
}
