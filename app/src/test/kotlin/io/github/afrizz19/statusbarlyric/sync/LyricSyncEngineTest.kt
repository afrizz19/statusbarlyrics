package io.github.afrizz19.statusbarlyric.sync

import io.github.afrizz19.statusbarlyric.api.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricSyncEngineTest {

    private val lines = listOf(
        SyncedLine(0, "line0"),
        SyncedLine(1000, "line1"),
        SyncedLine(2500, "line2"),
        SyncedLine(5000, "line3"),
    )
    private val engine = LyricSyncEngine(lines)

    @Test fun beforeFirstLine_returnsNull() {
        val e = LyricSyncEngine(listOf(SyncedLine(1000, "a")))
        assertEquals(-1, e.activeIndexAt(500))
        assertNull(e.lineAt(500))
        assertEquals("", e.textAt(500))
    }

    @Test fun exactBoundary_isActive() {
        assertEquals("line1", engine.textAt(1000))
        assertEquals("line2", engine.textAt(2500))
    }

    @Test fun betweenLines_takesEarlier() {
        assertEquals("line1", engine.textAt(2499))
        assertEquals("line2", engine.textAt(4999))
    }

    @Test fun afterLastLine_staysOnLast() {
        assertEquals("line3", engine.textAt(999_999))
    }

    @Test fun unsortedInput_isSorted() {
        val e = LyricSyncEngine(listOf(SyncedLine(2000, "b"), SyncedLine(500, "a")))
        assertEquals("a", e.textAt(600))
        assertEquals("b", e.textAt(2100))
    }

    @Test fun emptyEngine() {
        val e = LyricSyncEngine(emptyList())
        assertEquals("", e.textAt(1000))
    }

    @Test fun activeLineHasNextLineAsEnd() {
        val a = engine.activeLineAt(1200)!!
        assertEquals(1, a.index)
        assertEquals("line1", a.text)
        assertEquals(1000, a.startMs)
        assertEquals(2500, a.endMs) // next line's start
    }

    @Test fun lastLineUsesDefaultDuration() {
        val e = LyricSyncEngine(listOf(SyncedLine(1000, "only")), lastLineDurationMs = 4000)
        val a = e.activeLineAt(1500)!!
        assertEquals(1000, a.startMs)
        assertEquals(5000, a.endMs)
    }

    @Test fun activeLineNullBeforeFirst() {
        assertNull(engine.activeLineAt(-1))
    }
}
