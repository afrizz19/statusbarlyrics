package io.github.afrizz19.statusbarlyric.api

import org.junit.Assert.*
import org.junit.Test

class TranslationFilterTest {

    /** U+4E00-range Han, built from code points so this file stays pure ASCII. */
    private fun han(vararg cp: Int) = String(cp.map { it.toChar() }.toCharArray())

    private fun line(text: String, start: Long) = SyncedLine(startMs = start, text = text)

    @Test
    fun `strips interleaved chinese translations from a bilingual track`() {
        // Shape of the real Temper City - Self Aware response: every English line is
        // followed by a Han rendering of it.
        val lines = mutableListOf<SyncedLine>()
        var t = 1000L
        repeat(20) { i ->
            lines += line("English line $i", t)
            t += 1000
            lines += line(han(0x65E0, 0x706B, 0x4FBF, 0x70DF), t)
            t += 1000
        }

        val out = TranslationFilter.strip(lines)

        assertEquals(20, out.size)
        assertTrue(out.all { it.text.startsWith("English line") })
        // Order and timestamps of the survivors are untouched.
        assertEquals(1000L, out[0].startMs)
        assertEquals(3000L, out[1].startMs)
    }

    @Test
    fun `keeps everything for an all-chinese track`() {
        // A genuinely Chinese song: stripping Han here would leave nothing.
        val lines = (0 until 12).map { line(han(0x6211, 0x559C, 0x6B22), it * 1000L) }
        val out = TranslationFilter.strip(lines)
        assertEquals(12, out.size)
    }

    @Test
    fun `keeps everything for a chinese track with a few english lines`() {
        val lines = mutableListOf<SyncedLine>()
        repeat(18) { lines += line(han(0x6211, 0x559C, 0x6B22), it * 1000L) }
        repeat(2) { lines += line("Oh yeah", 20000L + it * 1000L) }
        val out = TranslationFilter.strip(lines)
        assertEquals("a mostly-Chinese track is not a translation pair", 20, out.size)
    }

    @Test
    fun `leaves a pure english track untouched`() {
        val lines = (0 until 10).map { line("Line $it", it * 1000L) }
        val out = TranslationFilter.strip(lines)
        assertSame(lines, out)
    }

    @Test
    fun `keeps a mixed line where latin dominates`() {
        // One Han character in an otherwise Latin line is not a translation.
        val mixed = "Self Aware " + han(0x81EA, 0x77E5)
        assertFalse(TranslationFilter.isHanDominant(mixed))
    }

    @Test
    fun `detects han-only and han-with-punctuation lines`() {
        assertTrue(TranslationFilter.isHanDominant(han(0x65E0, 0x706B)))
        // Fullwidth-parenthesised stage direction, e.g. the instrumental-break marker.
        assertTrue(TranslationFilter.isHanDominant("(" + han(0x95F4, 0x594F) + ")"))
    }

    @Test
    fun `latin and empty text are never han-dominant`() {
        assertFalse(TranslationFilter.isHanDominant("No smoke with no fire"))
        assertFalse(TranslationFilter.isHanDominant(""))
        assertFalse(TranslationFilter.isHanDominant("[Chorus]"))
    }

    @Test
    fun `empty input returns empty`() {
        assertTrue(TranslationFilter.strip(emptyList()).isEmpty())
    }
}
