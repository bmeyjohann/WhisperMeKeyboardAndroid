package app.whisperme.keyboard.ime.voice

import app.whisperme.keyboard.app.settings.voiceinput.VoiceLlmProvider
import app.whisperme.keyboard.app.settings.voiceinput.compatibleModel
import app.whisperme.keyboard.app.settings.voiceinput.compatibleVoiceReasoning
import app.whisperme.keyboard.app.settings.voiceinput.supportedVoiceReasoning
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceRecoveryProtocolTest {
    @Test fun delayedOrUnknownPongsCannotInventGoodInternet() {
        val health = VoiceConnectionHealth()
        val first = health.ping(0)
        assertFalse(health.pong(99, 100))
        health.ping(5000)
        health.ping(10000)
        assertEquals(1, health.missed)
        assertTrue(health.description(false).contains("cause unknown"))
        assertFalse(health.pong(first, 11000))
        health.ping(15000)
        assertTrue(health.description(false).contains("interrupted"))
        val fresh = health.ping(16000)
        assertTrue(health.pong(fresh, 16080))
        assertEquals(80L, health.rttMs)
        assertEquals(0, health.missed)
        assertTrue(health.description(true).contains("device is offline"))
    }

    @Test fun fieldMetadataAloneCannotAuthorizeRecoveryInAnotherEditor() {
        assertTrue(sameVoiceField(true, true, true, true))
        // A restart with a new editor object cannot authorize an anonymous WebView field.
        assertFalse(sameVoiceField(false, true, true, true))
        assertFalse(sameVoiceField(true, false, true, true))
        assertFalse(sameVoiceField(true, true, false, true))
        assertFalse(sameVoiceField(true, true, true, false))
    }

    @Test fun completedAsrAndRoutingSurviveRestartWithoutEditorOrCredentials() {
        val payload = VoiceRecoveryPayload("Final transcript", "server", "default", mode = "auto")
        val entry = VoiceHistoryEntry(transport = "realtime", transcriptionProvider = "meta", transcriptionModel = "muse",
            llmProvider = "default", llmModel = "", mode = "auto", finalAsr = payload.transcript, recovery = payload)
        val json = Json.encodeToString(entry)
        assertTrue(json.contains("\"startedAt\""))
        assertTrue(json.contains("\"id\""))
        assertEquals(entry, Json.decodeFromString<VoiceHistoryEntry>(json))
        listOf("audio", "token", "context", "instructions").forEach { assertFalse(json.contains(it)) }
        assertTrue(payload.valid())
        assertFalse(payload.copy(transcript = "").valid())
        assertFalse(payload.copy(transcript = "a".repeat(50_001)).valid())
        assertFalse(payload.copy(llmModel = "stale-client-model").valid())
        assertTrue(entry.summary().contains("continue"))
        assertTrue(entry.canContinueFinalProcessing())
        assertFalse(entry.copy(status = "successful").canContinueFinalProcessing())
        assertFalse(entry.copy(status = "unverified").canContinueFinalProcessing())
        assertFalse(entry.copy(result = "already applied").canContinueFinalProcessing())
        assertFalse(entry.copy(failure = VoiceFailure(retryable = false)).canContinueFinalProcessing())
        val budget = entry.copy(failure = VoiceFailure(code = "budget_exhausted", stage = "llm", retryable = false))
        assertTrue(budget.summary().contains("budget exhausted"))
        assertFalse(budget.canContinueFinalProcessing())
        assertEquals(budget, Json.decodeFromString<VoiceHistoryEntry>(Json.encodeToString(budget)))
        assertTrue(entry.copy(recovery = null, finalAsr = null, transcript = "partial").summary().contains("incomplete"))
    }

    @Test fun serverDefaultDoesNotPinAModelAndExplicitPreferencesStillResolve() {
        assertEquals("default", VoiceLlmProvider.SERVER_DEFAULT.wireValue)
        assertEquals("", VoiceLlmProvider.SERVER_DEFAULT.compatibleModel("saved-explicit-model"))
        assertEquals("gpt-oss-120b", VoiceLlmProvider.CEREBRAS_GLM_4_7.compatibleModel("gpt-oss-120b"))
    }

    @Test fun resultCheckpointSurvivesBeforeFinalInsertion() {
        val entry = VoiceHistoryEntry(transport = "realtime", transcriptionProvider = "meta", transcriptionModel = "muse",
            llmProvider = "default", llmModel = "", mode = "auto")
        assertEquals("finished", entry.sample("llm.result", 1, "finished", 100).result)
    }
    @Test fun reasoningNeverSendsUnsupportedSavedLevelsOrPinsDefault() {
        assertEquals(listOf("default"), supportedVoiceReasoning(null))
        assertEquals("default", compatibleVoiceReasoning("gpt-oss-120b", "none"))
        assertEquals("default", compatibleVoiceReasoning("openai/gpt-oss-20b", "unknown"))
        assertEquals("none", compatibleVoiceReasoning("qwen/qwen3.8-27b", "none"))
        assertEquals("high", compatibleVoiceReasoning("qwen-3.8-27b", "high"))
        assertEquals("none", compatibleVoiceReasoning("gemma-4-31b", "none"))
        val payload = VoiceRecoveryPayload("saved", "explicit", "cerebras", "qwen-3.8-27b", "auto", "high")
        assertEquals("high", Json.decodeFromString<VoiceRecoveryPayload>(Json.encodeToString(payload)).reasoningEffort)
    }

}
