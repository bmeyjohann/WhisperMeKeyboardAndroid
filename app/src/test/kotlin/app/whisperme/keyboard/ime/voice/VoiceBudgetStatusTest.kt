package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceBudgetStatusTest {
    private val budget = """{"warning":true,"committedUsd":8.1,"limitUsd":10,"resetsAt":"2026-10-01T00:00:00.000Z"}"""
    @Test fun batchSuccessAndFailureBothRetainBudgetAndAsr() {
        for (status in listOf(200, 429, 503)) {
            val result = parseVoiceBatchResult("""{"success":true,"transcription":{"text":"saved"},"finalText":"result","budget":$budget}""", status)
            assertEquals("saved", result.transcription)
            assertEquals(8.1, result.budget!!.committedUsd)
            assertTrue(result.budget.warningText()!!.contains("$8.10 of $10.00"))
        }
    }
    @Test fun transcriptStyleAndWebsocketEnvelopesHaveSameSafeAccounting() {
        val transcript = parseVoiceBudgetStatus("""{"finalText":"result","budget":$budget}""")
        val style = parseVoiceBudgetStatus("""{"instructions":"private instruction","sample":"private sample","token":"secret","budget":$budget}""")
        val websocket = parseVoiceBudgetStatus("""{"type":"budget.warning","budget":$budget}""")
        assertEquals(transcript, style)
        assertEquals(style, websocket)
        val encoded = Json.encodeToString(style)
        assertFalse(encoded.contains("private"))
        assertFalse(encoded.contains("secret"))
        assertEquals(style, Json.decodeFromString<VoiceBudgetStatus>(encoded))
    }
    @Test fun nonWarningClearsNoticeAndMissingAccountingIsNotZero() {
        assertNull(parseVoiceBudgetStatus("""{"budget":{"warning":false}}""")!!.warningText())
        val missing = parseVoiceBudgetStatus("""{"budget":{"warning":true}}""")!!
        assertNull(missing.committedUsd)
        assertNull(missing.limitUsd)
        assertTrue(missing.warningText()!!.contains("unavailable"))
        assertNull(parseVoiceBudgetStatus("{}"))
        assertNull(parseVoiceBudgetStatus("not json"))
    }
    @Test fun budgetSurvivesHistorySerializationIndependentlyOfOtherNotices() {
        val entry = VoiceHistoryEntry(startedAt = 1, transport = "batch", transcriptionProvider = "groq", transcriptionModel = "default",
            llmProvider = "default", llmModel = "", mode = "auto", notice = "Preference saved", budget = parseVoiceBudgetStatus("""{"budget":$budget}"""))
        assertEquals(entry, Json.decodeFromString<VoiceHistoryEntry>(Json.encodeToString(entry)))
        assertNotNull(entry.budget!!.warningText())
    }
}
