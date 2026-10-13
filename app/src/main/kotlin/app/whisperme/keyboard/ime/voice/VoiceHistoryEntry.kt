package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class VoiceHistorySample(val kind: String, val revision: Long, val elapsedMs: Long, val text: String, val truncated: Boolean)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class VoiceHistoryEntry(
    @kotlinx.serialization.EncodeDefault
    val id: String = UUID.randomUUID().toString(),
    @kotlinx.serialization.EncodeDefault
    val startedAt: Long = System.currentTimeMillis(),
    val transport: String,
    val transcriptionProvider: String,
    val transcriptionModel: String,
    val llmProvider: String,
    val llmModel: String,
    val mode: String,
    val status: String = "interrupted",
    val elapsedMs: Long = 0,
    val recordingMs: Long? = null,
    val firstTranscriptMs: Long? = null,
    val finalAsrMs: Long? = null,
    val resultMs: Long? = null,
    val transcript: String = "",
    val finalAsr: String? = null,
    val result: String? = null,
    val error: String? = null,
    val samples: List<VoiceHistorySample> = emptyList(),
    val omittedSamples: Int = 0,
    val usageJson: String? = null,
    val diagnostics: VoiceDiagnostics? = null,
    val notice: String? = null,
    val recovery: VoiceRecoveryPayload? = null,
    val failure: VoiceFailure? = null,
    val resolvedLlm: Boolean = false,
    val reasoningEffort: String = "default",
    val budget: VoiceBudgetStatus? = null,
) {
    fun sample(kind: String, revision: Long, text: String, elapsed: Long): VoiceHistoryEntry {
        val sample = VoiceHistorySample(kind, revision, elapsed, text.take(4000), text.length > 4000)
        return copy(samples = (samples + sample).takeLast(200),
            omittedSamples = omittedSamples + if (samples.size >= 200) 1 else 0,
            transcript = if (kind.startsWith("transcript")) text else transcript,
            result = if (kind == "llm.result") text else result,
            firstTranscriptMs = firstTranscriptMs ?: elapsed.takeIf { kind.startsWith("transcript") },
            finalAsr = if (kind == "transcript.final") text else finalAsr,
            finalAsrMs = if (kind == "transcript.final") elapsed else finalAsrMs)
    }
}

internal fun retainVoiceHistory(entries: List<VoiceHistoryEntry>, entry: VoiceHistoryEntry) =
    (entries.filterNot { it.id == entry.id } + entry).sortedByDescending { it.startedAt }
