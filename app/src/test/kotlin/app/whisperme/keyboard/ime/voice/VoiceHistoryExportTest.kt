package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceHistoryExportTest {
    private fun entry(id: String) = VoiceHistoryEntry(id = id, startedAt = 123,
        transport = "realtime", transcriptionProvider = "mistral", transcriptionModel = "voxtral",
        llmProvider = "cerebras", llmModel = "qwen", mode = "auto")

    @Test fun exportExcludesAllNonAllowlistedHistoryEvenWhenDiagnosticsAreEnabled() {
        val secret = "PRIVATE_SENTINEL"
        val value = entry("safe-case").copy(
            transcript = "old partial", finalAsr = "Chosen dictation", result = "Chosen result",
            error = secret, notice = secret, usageJson = "{\"token\":\"$secret\"}",
            samples = listOf(VoiceHistorySample("request", 1, 10, secret, false)),
            diagnostics = VoiceDiagnostics(truncated = false,
                entries = listOf(VoiceDiagnosticEntry("prompt", "llm", JsonPrimitive(secret))),
                contextSources = JsonPrimitive(secret)),
            recovery = VoiceRecoveryPayload(secret, "server", "default", mode = "auto"),
            failure = VoiceFailure(code = secret),
        )
        val encoded = exportVoiceHistory(listOf(value), listOf(value.id), 456)
        assertFalse(encoded.contains(secret))
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("format", "schemaVersion", "exportedAtEpochMs", "cases"), root.keys)
        assertEquals("whisperme.history", root.getValue("format").jsonPrimitive.content)
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.int)
        val case = root.getValue("cases").jsonArray.single().jsonObject
        assertEquals(setOf("caseId", "startedAtEpochMs", "transcript", "transcriptFinal", "result", "status",
            "transport", "mode", "transcriptionProvider", "transcriptionModel", "llmProvider", "llmModel",
            "llmModelResolved", "reasoningEffort", "timings"), case.keys)
        assertEquals("Chosen dictation", case.getValue("transcript").jsonPrimitive.content)
        assertTrue(case.getValue("transcriptFinal").jsonPrimitive.boolean)
    }

    @Test fun roundTripPreservesFullUnicodeTextModelAndTimingsWithoutInventingMissingValues() {
        val text = "Grüße 👋 \"quoted\"\n".repeat(1000)
        val value = entry("unicode").copy(finalAsr = text, result = text + "!", status = "recovered",
            elapsedMs = 2500, firstTranscriptMs = 0, finalAsrMs = 900, resultMs = 2400,
            resolvedLlm = true, reasoningEffort = "high")
        val decoded = Json.decodeFromString<VoiceHistoryExport>(exportVoiceHistory(listOf(value), listOf(value.id), 456))
        assertEquals(456L, decoded.exportedAtEpochMs)
        val case = decoded.cases.single()
        assertEquals(text, case.transcript)
        assertEquals(text + "!", case.result)
        assertEquals(123L, case.startedAtEpochMs)
        assertTrue(case.llmModelResolved)
        assertEquals("high", case.reasoningEffort)
        assertEquals("recovered", case.status)
        assertEquals(VoiceHistoryExportTimings(2500, null, 0, 900, 2400), case.timings)
    }

    @Test fun onlySelectedCasesExportWithStableIdsAndPartialTextExplicitlyMarked() {
        val entries = listOf(entry("a"), entry("b").copy(transcript = "unfinished"), entry("c"))
        val exported = Json.decodeFromString<VoiceHistoryExport>(exportVoiceHistory(entries, listOf("b", "a", "b"), 1))
        assertEquals(listOf("b", "a"), exported.cases.map { it.caseId })
        assertEquals("unfinished", exported.cases.first().transcript)
        assertFalse(exported.cases.first().transcriptFinal)
        assertNull(exported.cases.first().result)
        assertEquals(3, Json.decodeFromString<VoiceHistoryExport>(exportVoiceHistory(entries, entries.map { it.id }, 1)).cases.size)
    }

    @Test fun missingOrEmptySelectionFailsInsteadOfSilentlyExportingOtherSessions() {
        assertThrows(IllegalArgumentException::class.java) { exportVoiceHistory(listOf(entry("a")), emptyList(), 1) }
        assertThrows(IllegalArgumentException::class.java) { exportVoiceHistory(listOf(entry("a")), listOf("deleted"), 1) }
    }
}
