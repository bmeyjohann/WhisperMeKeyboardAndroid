package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceBatchResultTest {
    @Test fun http500KeepsFullAsrAndLlmError() {
        val result = parseVoiceBatchResult("""{"success":false,"error":"LLM quota exceeded","transcription":{"text":"Hello \"world\"\nGrüße"}}""", 500)
        assertFalse(result.success)
        assertEquals("Hello \"world\"\nGrüße", result.transcription)
        assertEquals("HTTP 500: LLM quota exceeded", result.error)
        assertNull(result.finalText)
    }
    @Test fun successRetainsEmptyResultAndNonSuccessHttpCannotClaimSuccess() {
        val body = """{"success":true,"transcription":{"text":"speech"},"finalText":""}"""
        assertEquals("", parseVoiceBatchResult(body).finalText)
        assertTrue(parseVoiceBatchResult(body).success)
        assertFalse(parseVoiceBatchResult(body, 503).success)
    }
    @Test fun malformedErrorsRemainFailuresWithoutPersistingRawResponseBodies() {
        val result = parseVoiceBatchResult("<html>private gateway details</html>", 502)
        assertFalse(result.success)
        assertTrue(result.error!!.contains("HTTP 502"))
        assertFalse(result.error.contains("private gateway"))
    }
}
