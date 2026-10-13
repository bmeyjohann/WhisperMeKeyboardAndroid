package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceDiagnosticsTest {
    private val raw = """{"version":1,"truncated":false,"entries":[{"kind":"context","stage":"formatting","data":{"fullText":"editor context","token":"secret-value","nested":{"audio":"audio-value","note":"Bearer abc.def"}}}]}"""
    @Test fun disabledOptInDropsDiagnostics() { assertNull(parseVoiceDiagnostics(raw, false)) }
    @Test fun explicitContextSurvivesButCredentialsAndAudioAreRedacted() {
        val diagnostic = checkNotNull(parseVoiceDiagnostics(raw, true))
        val encoded = Json.encodeToString(diagnostic)
        assertTrue(encoded.contains("editor context"))
        for (secret in listOf("secret-value", "audio-value", "abc.def")) assertFalse(encoded.contains(secret))
        assertEquals(diagnostic, Json.decodeFromString<VoiceDiagnostics>(encoded))
    }
    @Test fun malformedOrUnsupportedEnvelopeIsIgnored() {
        assertNull(parseVoiceDiagnostics("broken", true))
        assertNull(parseVoiceDiagnostics(raw.replace("\"version\":1", "\"version\":2"), true))
    }
    @Test fun oversizeIsExplicitlyTruncated() {
        assertTrue(checkNotNull(parseVoiceDiagnostics("x".repeat(600_001), true)).truncated)
    }
    @Test fun fullAvailableContextAndSourcesSurviveAndEmbeddedSecretsAreRedacted() {
        val text = "context ".repeat(15000)
        val raw = kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(1))
            put("truncated", kotlinx.serialization.json.JsonPrimitive(false))
            put("contextSources", Json.parseToJsonElement("""{"historyMessagesIncluded":0,"editorWindow":{"utf16Length":120000,"start":0,"totalLength":120000,"insertOnly":false},"relatedFields":[],"expansions":[]}"""))
            put("entries", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive("context"))
                put("stage", kotlinx.serialization.json.JsonPrimitive("formatting"))
                put("data", kotlinx.serialization.json.buildJsonObject {
                    put("fullText", kotlinx.serialization.json.JsonPrimitive(text))
                    put("serialized", kotlinx.serialization.json.JsonPrimitive("""{"secret":"remove-me","text":"keep-me"}"""))
                })
            }) })
        }.toString()
        val parsed = checkNotNull(parseVoiceDiagnostics(raw, true))
        val encoded = Json.encodeToString(parsed)
        assertTrue(encoded.contains(text))
        assertNotNull(parsed.contextSources)
        assertFalse(parsed.truncated)
        assertFalse(encoded.contains("remove-me"))
        assertTrue(encoded.contains("keep-me"))
    }
    @Test fun oldHistoryRemainsReadableAndMetadataIsSeparateFromResult() {
        val old = """{"transport":"batch","transcriptionProvider":"groq","transcriptionModel":"m","llmProvider":"groq","llmModel":"m","mode":"auto","result":"insert me"}"""
        val entry = Json.decodeFromString<VoiceHistoryEntry>(old)
        assertNull(entry.diagnostics)
        val updated = entry.copy(diagnostics = parseVoiceDiagnostics(raw, true), notice = "Preference saved")
        val restored = Json.decodeFromString<VoiceHistoryEntry>(Json.encodeToString(updated))
        assertEquals("insert me", restored.result)
        assertEquals("Preference saved", restored.notice)
        assertNotNull(restored.diagnostics)
    }
}
