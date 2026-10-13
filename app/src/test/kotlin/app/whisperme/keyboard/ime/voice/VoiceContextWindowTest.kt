package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceContextWindowTest {
    @Test fun largeFieldPreservesBothSidesAndPositionsCursor() {
        val original = "a".repeat(400_000) + "selected" + "z".repeat(400_000)
        val window = voiceContextWindow(original, 400_000, 400_008)
        assertEquals(4008, window.end - window.start)
        val (result, cursor) = window.apply(original, "replacement", 4)
        assertEquals(original.take(window.start) + "replacement" + original.substring(window.end), result)
        assertEquals(window.start + 4, cursor)
        assertEquals(window.start + 11, window.apply(original, "replacement", null).second)
        assertTrue(window.hasBefore && window.hasAfter)
    }

    @Test fun utf16BoundariesNeverSplitEmoji() {
        val original = "x" + "😀".repeat(3000)
        val window = voiceContextWindow(original, 3001, 3001)
        assertFalse(splitsSurrogate(original, window.start))
        assertFalse(splitsSurrogate(original, window.end))
        assertThrows(IllegalArgumentException::class.java) { voiceContextWindow(original, 3000, 3000) }
        assertThrows(IllegalArgumentException::class.java) { window.apply(original, "😀", 1) }
    }

    @Test fun boundsAndOversizedSelectionFailClearly() {
        assertThrows(IllegalArgumentException::class.java) { voiceContextWindow("x".repeat(9000), 0, 8001) }
        assertThrows(IllegalArgumentException::class.java) { voiceContextWindow("x".repeat(1_000_001), 0, 0) }
        val window = voiceContextWindow("hello", 5, 5)
        assertEquals(0, window.start)
        assertEquals(5, window.end)
        assertThrows(IllegalArgumentException::class.java) { window.apply("hello", "x", 2) }
    }

    @Test fun roleRequiresFieldEvidence() {
        assertEquals("email_subject", voiceFieldRole("text", "Betreff", null))
        assertEquals("email_body", voiceFieldRole("multiline_text", null, "Message body"))
        assertEquals("unknown", voiceFieldRole("email_app", null, null))
        assertEquals("text", voiceFieldRole("multiline_text", "Write a message", null))
        assertEquals("email_address", voiceFieldRole("email", "Subject", null))
    }

    @Test fun expansionUsesOriginalSnapshotAndPreservesOutsideLatestWindow() {
        val text = "a".repeat(20_000) + "chosen" + "b".repeat(20_000)
        val initial = voiceContextWindow(text, 20_000, 20_006)
        val expanded = voiceContextWindow(text, 20_000, 20_006, 12_000, 9_000)
        assertTrue(expanded.start < initial.start && expanded.end > initial.end)
        assertEquals(21_006, expanded.end - expanded.start)
        val (result, cursor) = expanded.apply(text, "updated", null)
        assertEquals("a".repeat(8_000) + "updated" + "b".repeat(11_000), result)
        assertEquals(8_007, cursor)
        assertThrows(IllegalArgumentException::class.java) { voiceContextWindow(text, 20_000, 20_006, 12_001, 2000) }
    }
    @Test fun actualWindowContainmentAllowsZeroAtDocumentEdges() {
        val text = "a".repeat(20_000)
        val atEnd = voiceContextWindow(text, text.length, text.length)
        // Real worker request: {beforeChars:12000,afterChars:0}.
        assertTrue(voiceContextWindow(text, text.length, text.length, 12_000, 0).contains(atEnd))
        val atStart = voiceContextWindow(text, 0, 0)
        assertTrue(voiceContextWindow(text, 0, 0, 0, 12_000).contains(atStart))
        val middle = voiceContextWindow(text, 10_000, 10_000)
        assertFalse(voiceContextWindow(text, 10_000, 10_000, 12_000, 0).contains(middle))
    }
}
