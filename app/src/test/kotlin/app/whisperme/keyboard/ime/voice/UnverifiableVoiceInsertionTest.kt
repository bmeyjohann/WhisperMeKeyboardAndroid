package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UnverifiableVoiceInsertionTest {
    @Test fun completeUnicodeTranscriptIsCommittedExactlyOnce() {
        val text = "A long complete transcript 😀\n".repeat(500)
        val writes = mutableListOf<String>()
        val notice = commitUnverifiableVoiceInsertion(text, false) { writes += it; true }
        assertEquals(listOf(text), writes)
        assertTrue(notice.contains("cannot confirm"))
    }
    @Test fun missingFinalAndEditWindowNeverWrite() {
        for ((text, window) in listOf(null to false, "replacement document" to true, "" to false)) {
            commitUnverifiableVoiceInsertion(text, window) { fail<Unit>("Must not write"); false }
        }
    }
    @Test fun rejectedOrThrowingCommitIsNeverRetried() {
        for (throws in listOf(false, true)) {
            var writes = 0
            val notice = commitUnverifiableVoiceInsertion("complete", false) {
                writes++
                if (throws) throw IllegalStateException("gone")
                false
            }
            assertEquals(1, writes)
            assertTrue(notice.contains("Check the field"))
        }
    }
}
