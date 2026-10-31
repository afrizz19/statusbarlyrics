package io.github.afrizz19.statusbarlyric.api

/** A single word with an absolute time window (ms from track start). */
data class TimedWord(
    val word: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * A synced lyric line with its resolved absolute start time in ms.
 *
 * [endMs] and [words] are only populated by providers that publish them (lrcmux at
 * word level); the private line-level API leaves both null, and every consumer treats
 * null as "derive it" / "no word data".
 */
data class SyncedLine(
    val startMs: Long,
    val text: String,
    /** The provider's real end for this line. Null = derive from the next line's start. */
    val endMs: Long? = null,
    /**
     * Per-word timings whose texts concatenate back to [text] exactly, so a character
     * index into a word maps 1:1 onto [text]. Null = no word data for this line.
     */
    val words: List<TimedWord>? = null,
)

/** How precise the timing actually returned by a provider is. */
enum class SyncLevel { WORD, LINE, NONE }

/** A fetch result, carrying the level that was actually served (not the one asked for). */
data class LyricsResult(
    val lines: List<SyncedLine>,
    val level: SyncLevel,
    /** Provider-reported upstream source, for logging. Null if unknown. */
    val source: String? = null,
)
