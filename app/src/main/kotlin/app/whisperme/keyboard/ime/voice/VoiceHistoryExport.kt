package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Deliberate allowlist: never serialize the diagnostic/history entry itself into an export. */
@Serializable
data class VoiceHistoryExport(
    val format: String = "whisperme.history",
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val cases: List<VoiceHistoryExportCase>,
)

@Serializable
data class VoiceHistoryExportCase(
    val caseId: String,
    val startedAtEpochMs: Long,
    val transcript: String,
    val transcriptFinal: Boolean,
    val result: String?,
    val status: String,
    val transport: String,
    val mode: String,
    val transcriptionProvider: String,
    val transcriptionModel: String,
    val llmProvider: String,
    val llmModel: String,
    val llmModelResolved: Boolean,
    val reasoningEffort: String,
    val timings: VoiceHistoryExportTimings,
)

@Serializable
data class VoiceHistoryExportTimings(
    val elapsedMs: Long,
    val recordingMs: Long?,
    val firstTranscriptMs: Long?,
    val finalAsrMs: Long?,
    val resultMs: Long?,
)

internal fun exportVoiceHistory(entries: List<VoiceHistoryEntry>, selectedIds: List<String>, now: Long): String {
    require(selectedIds.isNotEmpty()) { "Select at least one session." }
    val byId = entries.associateBy { it.id }
    val cases = selectedIds.distinct().map { id ->
        val entry = requireNotNull(byId[id]) { "A selected session is no longer available. Select sessions again." }
        VoiceHistoryExportCase(
            caseId = entry.id, startedAtEpochMs = entry.startedAt,
            transcript = entry.finalAsr ?: entry.transcript, transcriptFinal = entry.finalAsr != null,
            result = entry.result, status = entry.status, transport = entry.transport, mode = entry.mode,
            transcriptionProvider = entry.transcriptionProvider, transcriptionModel = entry.transcriptionModel,
            llmProvider = entry.llmProvider, llmModel = entry.llmModel, llmModelResolved = entry.resolvedLlm,
            reasoningEffort = entry.reasoningEffort,
            timings = VoiceHistoryExportTimings(entry.elapsedMs, entry.recordingMs, entry.firstTranscriptMs,
                entry.finalAsrMs, entry.resultMs),
        )
    }
    return Json { prettyPrint = true; encodeDefaults = true }.encodeToString(
        VoiceHistoryExport(exportedAtEpochMs = now, cases = cases))
}
