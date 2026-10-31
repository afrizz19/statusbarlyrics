package io.github.afrizz19.statusbarlyric.sync

import io.github.afrizz19.statusbarlyric.api.TimedWord

/**
 * Resolves how much of a line has been sung as a fractional character offset into the
 * line text. Pure logic — no Android dependencies.
 *
 * The karaoke highlight in the status bar clips the sung region: this produces the clip
 * boundary as a single float, which survives line-swap fades and is independently testable.
 */
object WordProgressResolver {

    /**
     * @param words per-word timings whose texts concatenate back to the line, so the
     *        returned offset indexes directly into the original line text.
     * @param positionMs absolute playback position.
     * @return the fractional character offset into the line that has been sung. 0f before
     *         the first word, line.text.length after the last word, and lerped across each
     *         word's `[start, end]` range. **In a gap between words, the offset holds at
     *         the previous word's end** rather than creeping forward — only the vocal
     *         advances the highlight.
     */
    fun charOffsetAt(words: List<TimedWord>, positionMs: Long): Float {
        if (words.isEmpty()) return 0f

        // Binary search for the active word (last word whose startMs <= positionMs).
        var lo = 0
        var hi = words.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid].startMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        // Before the first word.
        if (result < 0) return 0f

        val word = words[result]
        // Past the last word.
        if (result == words.lastIndex && positionMs >= word.endMs) {
            return words.sumOf { it.word.length }.toFloat()
        }

        // In a gap AFTER this word but before the next — hold at this word's end.
        if (positionMs >= word.endMs) {
            val c0 = words.take(result + 1).sumOf { it.word.length }
            return c0.toFloat()
        }

        // Within this word's [start, end] — lerp across its character range.
        val c0 = words.take(result).sumOf { it.word.length }
        val c1 = c0 + word.word.length
        val span = (word.endMs - word.startMs).coerceAtLeast(1)  // avoid div by zero
        val frac = (positionMs - word.startMs).toFloat() / span
        return c0 + frac * (c1 - c0)
    }
}
