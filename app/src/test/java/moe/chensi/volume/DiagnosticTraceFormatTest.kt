package moe.chensi.volume

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticTraceFormatTest {
    @Test
    fun formatsSurfaceImplementationAndEventDeterministically() {
        assertEquals(
            "42 surface=overlay impl=seekbar event=root_down x=1,y=2",
            DiagnosticTraceFormatter.format(
                timestampMs = 42,
                surface = "overlay",
                implementation = "seekbar",
                event = "root_down",
                details = "x=1,y=2"
            )
        )
    }

    @Test
    fun characterLimitDropsOldEntries() {
        val buffer = BoundedTraceBuffer(maxEntries = 10, maxCharacters = 8, maxLineCharacters = 8)

        buffer.append("abc")
        buffer.append("def")
        buffer.append("ghij")

        assertEquals("def\nghij", buffer.snapshot())
    }

    @Test
    fun entryLimitDropsOldEntries() {
        val buffer = BoundedTraceBuffer(maxEntries = 2, maxCharacters = 100, maxLineCharacters = 20)

        buffer.append("one")
        buffer.append("two")
        buffer.append("three")

        assertEquals("two\nthree", buffer.snapshot())
    }

    @Test
    fun prependKeepsCurrentSessionEntriesWhenOldTraceExceedsBuffer() {
        val buffer = BoundedTraceBuffer(maxEntries = 3, maxCharacters = 100, maxLineCharacters = 20)
        buffer.append("new-1")
        buffer.append("new-2")

        buffer.prepend("old-1\nold-2\nold-3\nold-4")

        assertEquals("old-4\nnew-1\nnew-2", buffer.snapshot())
    }

    @Test
    fun clearEmptiesBufferAndAdvancesRevision() {
        val buffer = BoundedTraceBuffer(maxEntries = 4, maxCharacters = 20, maxLineCharacters = 10)
        buffer.append("one")
        val beforeClear = buffer.snapshotWithRevision().revision

        buffer.clear()

        val afterClear = buffer.snapshotWithRevision()
        assertEquals(beforeClear + 1, afterClear.revision)
        assertEquals("", afterClear.text)
    }

    @Test
    fun bufferKeepsEachEntryOnOneBoundedLine() {
        val buffer = BoundedTraceBuffer(maxEntries = 4, maxCharacters = 12, maxLineCharacters = 6)

        buffer.append("a\nb\r\ncdefghi")

        assertEquals("a b  c", buffer.snapshot())
        assertEquals(6, buffer.snapshot().length)
    }
}
