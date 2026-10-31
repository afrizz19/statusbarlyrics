package io.github.afrizz19.statusbarlyric.api

/**
 * Strips interleaved Chinese translation lines out of a bilingual lyric.
 *
 * Some lrcmux sources (KuGou especially) return each sung line immediately followed by a
 * Chinese rendering of it, sharing the same stretch of the track:
 *
 * ```
 * 15092-17042  No smoke with no fire
 * 17042-18032  <Chinese translation of the line above>   <- not sung
 * 18032-20942  No silence if there's no sound
 * ```
 *
 * In the status bar that reads as the lyric flickering into Chinese between every line.
 *
 * Dropping the translations also *improves* the timing: a line's display end comes from the
 * next line's start ([io.github.afrizz19.statusbarlyric.sync.LyricSyncEngine]), so the English line now
 * stays up across the slot the translation occupied instead of being replaced by it. Its
 * `sungEndMs` is untouched, so the karaoke sweep still completes on the real vocal end and
 * then simply holds — the same thing it does through an instrumental gap.
 */
object TranslationFilter {

    /**
     * @return [lines] without its translation lines, or [lines] unchanged when the track
     *         doesn't look bilingual.
     */
    fun strip(lines: List<SyncedLine>): List<SyncedLine> {
        if (lines.isEmpty()) return lines

        val han = lines.count { isHanDominant(it.text) }
        if (han == 0) return lines

        // A Chinese track is *nearly all* Han lines, and stripping those would leave the
        // user with no lyric at all. Only read Han lines as translations when there's a
        // substantial body of non-Han lyric for them to be a translation of.
        val other = lines.size - han
        if (other < lines.size / 4) return lines

        return lines.filterNot { isHanDominant(it.text) }
    }

    /**
     * Whether [text] is Chinese rather than a Latin line that happens to contain a Han
     * character. Compared against the Latin letter count instead of using a fixed ratio so
     * a mixed line — a title, or a line with one CJK loanword — stays.
     */
    internal fun isHanDominant(text: String): Boolean {
        var han = 0
        var latin = 0
        for (c in text) {
            when {
                isHan(c) -> han++
                c.isLetter() -> latin++
            }
        }
        return han > 0 && han >= latin
    }

    /** Code points rather than char literals, to keep this file pure ASCII. */
    private fun isHan(c: Char): Boolean {
        val cp = c.code
        return cp in 0x4E00..0x9FFF ||  // CJK Unified Ideographs
            cp in 0x3400..0x4DBF ||     // Unified Ideographs Extension A
            cp in 0xF900..0xFAFF        // Compatibility Ideographs
    }
}
