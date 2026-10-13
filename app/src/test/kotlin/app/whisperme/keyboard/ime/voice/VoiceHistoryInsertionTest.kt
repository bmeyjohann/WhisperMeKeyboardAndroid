package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceHistoryInsertionTest {
    @Test fun rejectedSelectionDoesNotCommit() {
        var committed = false
        val error = applyVoiceHistoryResult(false, { false }, { committed = true; true }, { true })
        assertFalse(committed)
        assertTrue(error!!.contains("did not confirm the requested text selection"))
    }
    @Test fun rejectedCommitAndUnverifiedResultAreFailures() {
        assertTrue(applyVoiceHistoryResult(false, { true }, { false }, { true })!!.contains("commitText returned false"))
        assertTrue(applyVoiceHistoryResult(false, { true }, { true }, { false })!!.contains("did not confirm"))
    }
    @Test fun exceptionDetailsArePreservedAndSuccessRequiresVerification() {
        assertTrue(applyVoiceHistoryResult(false, { throw IllegalStateException("disconnected") }, { true }, { true })!!.contains("IllegalStateException: disconnected"))
        assertNull(applyVoiceHistoryResult(false, { true }, { true }, { true }))
        assertNull(applyVoiceHistoryResult(true, { error("must not edit") }, { error("must not edit") }, { false }))
    }
}
