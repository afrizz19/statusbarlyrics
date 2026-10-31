package io.github.afrizz19.statusbarlyric.sync

import io.github.afrizz19.statusbarlyric.api.SyncedLine
import io.github.afrizz19.statusbarlyric.api.TimedWord

/** The active line plus its resolved time window and position in the list. */
data class ActiveLine(
    val index: Int,
    val text: String,
    val startMs: Long,
    /** End = next line's start; for the last line, start + [DEFAULT_LAST_LINE_MS]. */
    val endMs: Long,
    /**
     * When the vocal actually finishes, which is the provider's own end when it publishes
     * one. Kept separate from [endMs] because during an instrumental gap the line stays on
     * screen long after the highlight should have completed.
     */
    val sungEndMs: Long = endMs,
    /** Per-word timings, or null when this line has none. */
    val words: List<TimedWord>? = null,
)

/**
 * Given time-sorted synced lyric lines, resolves which line is active at a
 * playback position. Pure logic — no Android dependencies, so it's unit-testable.
 */
class LyricSyncEngine(
    lines: List<SyncedLine>,
    private val lastLineDurationMs: Long = DEFAULT_LAST_LINE_MS,
) {

    private val lines: List<SyncedLine> = lines.sortedBy { it.startMs }

    /** Index of the active line for [positionMs], or -1 if before the first line. */
    fun activeIndexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        // Last line whose startMs <= positionMs (binary search).
        var lo = 0
        var hi = lines.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    fun lineAt(positionMs: Long): SyncedLine? =
        activeIndexAt(positionMs).takeIf { it >= 0 }?.let { lines[it] }

    fun textAt(positionMs: Long): String = lineAt(positionMs)?.text.orEmpty()

    /** Active line with its full time window, or null if before the first line / empty. */
    fun activeLineAt(positionMs: Long): ActiveLine? {
        val idx = activeIndexAt(positionMs)
        if (idx < 0) return null
        val line = lines[idx]
        val end = if (idx + 1 < lines.size) lines[idx + 1].startMs
        else line.startMs + lastLineDurationMs
        val sungEnd = line.endMs ?: end
        return ActiveLine(idx, line.text, line.startMs, end, sungEnd, line.words)
    }

    fun isEmpty(): Boolean = lines.isEmpty()

    companion object {
        const val DEFAULT_LAST_LINE_MS = 5_000L
    }
}
