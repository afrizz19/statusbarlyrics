package io.github.afrizz19.statusbarlyric.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingSynthesizerTest {

    @Test fun wordsSpanExactRange() {
        val words = WordTimingSynthesizer.synthesize("hello world", 1000, 3000)
        assertEquals(2, words.size)
        assertEquals(1000, words.first().startMs)
        assertEquals(3000, words.last().endMs)
    }

    @Test fun wordsAreContiguousAndMonotonic() {
        val words = WordTimingSynthesizer.synthesize("one two three four", 0, 4000)
        for (i in 1 until words.size) {
            // Each word starts where the previous ended (contiguous sweep).
            assertEquals(words[i - 1].endMs, words[i].startMs)
            assertTrue(words[i].startMs <= words[i].endMs)
        }
    }

    @Test fun longerWordsGetMoreTime() {
        // "a" (1 char) vs "abcdefghi" (9 chars) over 1000ms.
        val words = WordTimingSynthesizer.synthesize("a abcdefghi", 0, 1000)
        val first = words[0].endMs - words[0].startMs
        val second = words[1].endMs - words[1].startMs
        assertTrue("longer word should span more time", second > first)
    }

    @Test fun reconstructsOriginalText() {
        val text = "keep  the   spaces"
        val words = WordTimingSynthesizer.synthesize(text, 0, 1000)
        assertEquals(text, words.joinToString("") { it.word })
    }

    @Test fun zeroSpanCollapsesToInstant() {
        val words = WordTimingSynthesizer.synthesize("hi there", 500, 500)
        assertTrue(words.all { it.startMs == 500L && it.endMs == 500L })
    }

    @Test fun blankTextYieldsNothing() {
        assertTrue(WordTimingSynthesizer.synthesize("   ", 0, 1000).isEmpty())
    }
}
