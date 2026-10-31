package io.github.afrizz19.statusbarlyric.sync

import io.github.afrizz19.statusbarlyric.api.TimedWord
import org.junit.Assert.*
import org.junit.Test

class WordProgressResolverTest {

    @Test
    fun `before first word returns zero`() {
        val words = listOf(
            TimedWord("Hello ", 1000, 1500),
            TimedWord("world", 1500, 2000),
        )
        assertEquals(0f, WordProgressResolver.charOffsetAt(words, 500), 0.01f)
        assertEquals(0f, WordProgressResolver.charOffsetAt(words, 999), 0.01f)
    }

    @Test
    fun `at exact word start returns char position`() {
        val words = listOf(
            TimedWord("Hello ", 1000, 1500),
            TimedWord("world", 1500, 2000),
        )
        // "Hello " is 6 chars
        assertEquals(0f, WordProgressResolver.charOffsetAt(words, 1000), 0.01f)
        assertEquals(6f, WordProgressResolver.charOffsetAt(words, 1500), 0.01f)
    }

    @Test
    fun `halfway through word lerps to midpoint`() {
        val words = listOf(
            TimedWord("Hello ", 1000, 1500), // 6 chars, 500ms
        )
        // At 1250ms (50% through the word) → 0 + 0.5 * 6 = 3
        assertEquals(3f, WordProgressResolver.charOffsetAt(words, 1250), 0.01f)
    }

    @Test
    fun `in gap between words holds at previous word end`() {
        val words = listOf(
            TimedWord("Hello ", 1000, 1500),
            TimedWord("world", 2000, 2500), // gap 1500-2000
        )
        // At 1600ms (in the gap) → should hold at 6 (end of first word)
        assertEquals(6f, WordProgressResolver.charOffsetAt(words, 1600), 0.01f)
        assertEquals(6f, WordProgressResolver.charOffsetAt(words, 1999), 0.01f)
    }

    @Test
    fun `past last word returns full length`() {
        val words = listOf(
            TimedWord("Hello ", 1000, 1500),
            TimedWord("world", 1500, 2000),
        )
        // "Hello world" = 11 chars
        assertEquals(11f, WordProgressResolver.charOffsetAt(words, 2000), 0.01f)
        assertEquals(11f, WordProgressResolver.charOffsetAt(words, 3000), 0.01f)
    }

    @Test
    fun `zero-length window does not crash`() {
        val words = listOf(
            TimedWord("instant", 1000, 1000),
        )
        // Degenerate case; should not throw
        val offset = WordProgressResolver.charOffsetAt(words, 1000)
        assertTrue(offset >= 0f && offset <= 7f)
    }

    @Test
    fun `monotonic across swept range`() {
        val words = listOf(
            TimedWord("Killing ", 16842, 17003),
            TimedWord("me ", 17003, 17242),
            TimedWord("slow ", 17242, 17986),
            TimedWord("out ", 17986, 18202),
            TimedWord("the ", 18202, 18508),
            TimedWord("window", 18508, 19058),
        )
        var prev = -1f
        for (t in 16800..19100 step 50) {
            val offset = WordProgressResolver.charOffsetAt(words, t.toLong())
            assertTrue("offset must be non-decreasing", offset >= prev)
            prev = offset
        }
    }

    @Test
    fun `empty words returns zero`() {
        assertEquals(0f, WordProgressResolver.charOffsetAt(emptyList(), 1000), 0.01f)
    }
}
