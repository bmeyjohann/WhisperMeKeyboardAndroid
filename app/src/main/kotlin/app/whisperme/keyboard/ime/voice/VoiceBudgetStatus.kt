package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Locale

/** Only budget accounting metadata; never request contents or credentials. */
@Serializable
data class VoiceBudgetStatus(
    val warning: Boolean,
    val committedUsd: Double? = null,
    val limitUsd: Double? = null,
    val resetsAt: String? = null,
) {
    fun warningText(): String? {
        if (!warning) return null
        val amount = if (committedUsd != null && limitUsd != null)
            String.format(Locale.US, "$%.2f of $%.2f committed.", committedUsd, limitUsd)
            else "Usage details unavailable."
        return "WhisperMe monthly budget warning: $amount" + resetsAt?.let { " Resets $it." }.orEmpty()
    }
}

internal fun parseVoiceBudgetStatus(body: String): VoiceBudgetStatus? = runCatching {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
    val websocket = (root["type"] as? JsonPrimitive)?.contentOrNull == "budget.warning"
    val value = root["budget"] as? JsonObject ?: root.takeIf { websocket } ?: return null
    val warning = (value["warning"] as? JsonPrimitive)?.booleanOrNull ?: if (websocket) true else return null
    fun money(key: String) = (value[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
    VoiceBudgetStatus(warning, money("committedUsd"), money("limitUsd"),
        (value["resetsAt"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 128 })
}.getOrNull()
