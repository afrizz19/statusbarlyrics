package io.github.afrizz19.statusbarlyric.api

import org.junit.Assert.*
import org.junit.Test

class LrcMuxParserTest {

    @Test
    fun `parse Cruel Summer fragment with word timings`() {
        val body = """
        {
          "track": {
            "name": "Cruel Summer",
            "artists": ["Taylor Swift"],
            "album": "Lover",
            "duration": 178
          },
          "meta": {
            "level": "word",
            "instrumental": false,
            "source": { "id": "kugou", "name": "KuGou", "url": "https://kugou.com" }
          },
          "lines": [
            {
              "text": "Killing me slow out the window",
              "start": 16842,
              "end": 19344,
              "words": [
                { "text": "Killing ", "start": 16842, "end": 17003 },
                { "text": "me ", "start": 17003, "end": 17242 },
                { "text": "slow ", "start": 17242, "end": 17986 },
                { "text": "out ", "start": 17986, "end": 18202 },
                { "text": "the ", "start": 18202, "end": 18508 },
                { "text": "window", "start": 18508, "end": 19058 }
              ]
            }
          ]
        }
        """.trimIndent()

        val result = LrcMuxApiClient.parse(body)
        assertNotNull(result)
        assertEquals(SyncLevel.WORD, result!!.level)
        assertEquals("kugou", result.source)
        assertEquals(1, result.lines.size)

        val line = result.lines[0]
        assertEquals("Killing me slow out the window", line.text)
        assertEquals(16842L, line.startMs)
        assertEquals(19344L, line.endMs)

        val words = line.words
        assertNotNull(words)
        assertEquals(6, words!!.size)

        // Trailing spaces are preserved
        assertEquals("Killing ", words[0].word)
        assertEquals(16842L, words[0].startMs)
        assertEquals(17003L, words[0].endMs)

        assertEquals("me ", words[1].word)
        assertEquals("slow ", words[2].word)
        assertEquals("out ", words[3].word)
        assertEquals("the ", words[4].word)
        assertEquals("window", words[5].word)

        // Concatenation invariant
        val reconstructed = words.joinToString("") { it.word }
        assertEquals(line.text, reconstructed)
    }

    @Test
    fun `parse null lines returns empty`() {
        val body = """
        {
          "track": {},
          "meta": { "level": "none" },
          "lines": null
        }
        """.trimIndent()

        val result = LrcMuxApiClient.parse(body)
        assertNotNull(result)
        assertEquals(SyncLevel.NONE, result!!.level)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `parse instrumental returns empty with metadata`() {
        val body = """
        {
          "track": {},
          "meta": { "level": "line", "instrumental": true },
          "lines": []
        }
        """.trimIndent()

        val result = LrcMuxApiClient.parse(body)
        assertNotNull(result)
        assertEquals(SyncLevel.LINE, result!!.level)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `parse line with words null preserves text`() {
        val body = """
        {
          "track": {},
          "meta": { "level": "line" },
          "lines": [
            { "text": "No word data here", "start": 1000, "end": 2000, "words": null }
          ]
        }
        """.trimIndent()

        val result = LrcMuxApiClient.parse(body)
        assertNotNull(result)
        assertEquals(1, result!!.lines.size)

        val line = result.lines[0]
        assertEquals("No word data here", line.text)
        assertNull(line.words)
    }

    @Test
    fun `parse malformed JSON returns null`() {
        val result = LrcMuxApiClient.parse("not json at all")
        assertNull(result)
    }
}
