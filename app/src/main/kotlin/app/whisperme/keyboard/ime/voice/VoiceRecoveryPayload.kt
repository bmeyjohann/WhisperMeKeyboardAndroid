package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable

/** Portable continuation contains completed ASR only; never editor contents, prompts, audio or tokens.
 * The live manager keeps the original context separately for the lifetime of its field session.
 * After process loss/history selection continuation is copy-only, without the original editor context.
 */
@Serializable
data class VoiceRecoveryPayload(
    val transcript: String,
    val llmRouting: String,
    val llmProvider: String,
    val llmModel: String? = null,
    val mode: String,
    val reasoningEffort: String = "default",
) {
    fun valid() = transcript.isNotBlank() && transcript.length <= 50_000 &&
        reasoningEffort in setOf("default", "none", "low", "medium", "high") &&
        llmRouting in setOf("server", "explicit") && (llmRouting != "server" || llmModel == null)
}

@Serializable
data class VoiceFailure(
    val code: String = "unknown",
    val stage: String = "unknown",
    val provider: String? = null,
    val retryable: Boolean = false,
    val retryAfterMs: Long? = null,
)

fun VoiceHistoryEntry.summary(): String = when {
    status == "successful" -> "Text inserted"
    status == "recovered" -> "Recovered text · copy to use"
    result != null -> "Result saved · check field before copying"
    failure?.code == "budget_exhausted" -> if (finalAsr != null || transcript.isNotBlank())
        "Transcript saved · monthly budget exhausted" else "Monthly processing budget exhausted"
    recovery?.valid() == true && failure?.retryable == false -> "Transcript saved · provider needs attention"
    recovery?.valid() == true -> "Transcript saved · final processing can continue"
    finalAsr != null -> "Complete transcript saved · copy to use"
    transcript.isNotBlank() -> "Partial transcript saved · recording incomplete"
    status == "canceled" -> "Recording canceled"
    else -> "No transcript received"
}

fun VoiceHistoryEntry.canContinueFinalProcessing(): Boolean =
    recovery?.valid() == true && result == null && failure?.retryable != false &&
        status !in setOf("successful", "recovered", "unverified", "handled")
