package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.json.*

internal data class VoiceBatchResult(val success: Boolean, val transcription: String?, val finalText: String?, val error: String?, val budget: VoiceBudgetStatus? = null)

/** Keep ASR even when the server completed recognition but failed final LLM processing. */
internal fun parseVoiceBatchResult(body: String, httpStatus: Int = 200): VoiceBatchResult = try {
    val value = Json.parseToJsonElement(body).jsonObject
    val success = httpStatus in 200..299 && value["success"]?.jsonPrimitive?.booleanOrNull == true
    VoiceBatchResult(
        success = success,
        budget = parseVoiceBudgetStatus(body),
        transcription = (value["transcription"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull,
        finalText = value["finalText"]?.jsonPrimitive?.contentOrNull,
        error = value["error"]?.jsonPrimitive?.contentOrNull?.let { if (httpStatus in 200..299) it else "HTTP $httpStatus: $it" }
            ?: if (success) null else "HTTP $httpStatus: Voice processing failed",
    )
} catch (e: Exception) {
    VoiceBatchResult(false, null, null, "HTTP $httpStatus: Invalid voice response (${e.javaClass.simpleName})")
}
