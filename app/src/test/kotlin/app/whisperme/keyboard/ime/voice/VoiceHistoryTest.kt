package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceHistoryTest {
    private fun entry(start: Long = 1) = VoiceHistoryEntry(startedAt = start, transport = "realtime",
        transcriptionProvider = "mistral", transcriptionModel = "voxtral", llmProvider = "groq", llmModel = "qwen", mode = "auto")

    @Test fun boundedSamplesPreserveCompleteFinalAsrAndSeparateAssistant() {
        var value = entry()
        repeat(220) { value = value.sample("transcript.partial", it.toLong(), "x".repeat(5000), it.toLong()) }
        val full = "Final \"text\"\n".repeat(1000)
        value = value.sample("transcript.final", 81, full, 90)
        value = value.sample("assistant.message", 81, "Clarification", 91)
        assertEquals(200, value.samples.size)
        assertEquals(22, value.omittedSamples)
        assertTrue(value.samples.first().truncated)
        assertEquals(full, value.finalAsr)
        assertEquals(full, value.transcript)
        assertEquals("Clarification", value.samples.last().text)
        assertEquals(90L, value.finalAsrMs)
        assertEquals(0L, value.firstTranscriptMs)
    }
    @Test fun serializationPreservesFailuresResultsAndUnicodeWithoutContextFields() {
        val value = entry().sample("transcript.final", 2, "Hello \"world\"\nGrüße", 25)
            .copy(status = "failed", result = "Edited\ntext", error = "Field changed", elapsedMs = 40)
        val encoded = Json.encodeToString(value)
        assertEquals(value, Json.decodeFromString<VoiceHistoryEntry>(encoded))
        assertFalse(encoded.contains("audio"))
        assertFalse(encoded.contains("token"))
        assertFalse(encoded.contains("context"))
    }
    @Test fun usageSurvivesSerializationAndOldSessionsRemainReadable() {
        val value = entry().copy(usageJson = """{"calls":[{"inputTokens":100,"estimatedUsd":null}],"summary":"Cost unavailable"}""")
        assertEquals(value, Json.decodeFromString<VoiceHistoryEntry>(Json.encodeToString(value)))
        assertNull(Json.decodeFromString<VoiceHistoryEntry>(Json.encodeToString(entry())).usageJson)
    }
    @Test fun retentionKeepsAllSessionsAndUpdatesDoNotDuplicateOrChangeOrder() {
        var entries = emptyList<VoiceHistoryEntry>()
        repeat(110) { entries = retainVoiceHistory(entries, entry(it.toLong())) }
        assertEquals(110, entries.size)
        assertEquals(109L, entries.first().startedAt)
        assertEquals(0L, entries.last().startedAt)
        entries = retainVoiceHistory(entries, entries.last().copy(status = "canceled"))
        assertEquals(110, entries.size)
        assertEquals("canceled", entries.last().status)
    }
}
