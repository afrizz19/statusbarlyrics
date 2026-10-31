package io.github.afrizz19.statusbarlyric.sync

import io.github.afrizz19.statusbarlyric.api.TimedWord

/**
 * The private lyrics API returns LINE-level timing only (one timestamp per line).
 * Word-level karaoke needs a time window per word, so we synthesize one by
 * splitting a line's [startMs, endMs] span across its words, weighted by the
 * visible character length of each word (longer words get proportionally more time).
 *
 * This is an approximation — it won't match true per-word ("richsync") data, but it
 * produces a smooth left-to-right sweep that tracks the line's real duration.
 */
object WordTimingSynthesizer {

    /**
     * Split [text] into words spanning [startMs]..[endMs].
     * Whitespace is attached to the preceding word so reconstructed text is exact.
     * Returns an empty list if there is no usable text or a non-positive span.
     */
    fun synthesize(text: String, startMs: Long, endMs: Long): List<TimedWord> {
        if (text.isBlank()) return emptyList()
        val span = endMs - startMs
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()

        // Degenerate/zero span: give every word the same instant.
        if (span <= 0L) return tokens.map { TimedWord(it, startMs, startMs) }

        val weights = tokens.map { weightOf(it) }
        val totalWeight = weights.sum().coerceAtLeast(1)

        val result = ArrayList<TimedWord>(tokens.size)
        var acc = 0L
        for (i in tokens.indices) {
            val wStart = startMs + span * acc / totalWeight
            acc += weights[i]
            // Last word must land exactly on endMs to avoid rounding drift.
            val wEnd = if (i == tokens.lastIndex) endMs else startMs + span * acc / totalWeight
            result.add(TimedWord(tokens[i], wStart, wEnd.coerceAtLeast(wStart)))
        }
        return result
    }

    /**
     * Split on spaces but KEEP the trailing whitespace on each token, so that
     * concatenating all words reproduces the original line exactly (important for
     * receivers that render words back-to-back).
     */
    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var seenNonSpace = false
        for (ch in text) {
            sb.append(ch)
            if (ch == ' ') {
                if (seenNonSpace) {
                    out.add(sb.toString())
                    sb.setLength(0)
                    seenNonSpace = false
                }
                // leading/duplicate spaces just accumulate into the next token
            } else {
                seenNonSpace = true
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Weight = non-space character count, min 1 (so punctuation-only tokens still advance). */
    private fun weightOf(token: String): Long =
        token.count { !it.isWhitespace() }.toLong().coerceAtLeast(1)
}
