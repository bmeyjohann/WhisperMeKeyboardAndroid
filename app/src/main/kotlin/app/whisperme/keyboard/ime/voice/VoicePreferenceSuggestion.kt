package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

data class VoicePreferenceSuggestion(
    val kind: String,
    val text: String,
    // Identity captured when the request started, not whichever app has focus at save time.
    val packageName: String,
    val scope: String = "app",
    val requiresConfirmation: Boolean = false,
    val categoryId: String? = null,
    val scopeReason: String? = null,
    val name: String? = null,
    val scopeLocked: Boolean? = null,
) {
    val effectiveScopeLocked: Boolean get() = scopeLocked ?: requiresConfirmation
    val defaultAllApps: Boolean get() = scope == "all" && !requiresConfirmation

    internal fun saveScope(allApps: Boolean): String {
        require(packageName.isNotBlank()) { "The original app is unavailable. Record the request again in that app." }
        require(!effectiveScopeLocked || !allApps) { "This confirmation applies only to the original app." }
        return if (allApps) "all" else "app"
    }

    internal fun saveScope(selected: VoicePreferenceScope): VoicePreferenceScope {
        require(selected.valid()) { "Choose an app, a known category, or all apps." }
        require(selected.scope != "app" || selected.appIdentifier == packageName) { "Save this draft for the original app." }
        require(!effectiveScopeLocked || selected == VoicePreferenceScope("app", packageName)) {
            "This confirmation applies only to the original app."
        }
        return selected
    }

    val suggestedScope: VoicePreferenceScope get() = when (scope) {
        "category" -> VoicePreferenceScope("category", categoryId = categoryId)
        "all" -> VoicePreferenceScope()
        else -> if (kind == "dictionary" && packageName.isBlank()) VoicePreferenceScope() else VoicePreferenceScope("app", packageName)
    }
}

@Serializable
private data class PreferenceSuggestionPayload(
    val kind: String,
    val text: String,
    val scope: String = "app",
    val appIdentifier: String? = null,
    val requiresConfirmation: Boolean = false,
    val categoryId: String? = null,
    val scopeReason: String? = null,
    val name: String? = null,
    val scopeLocked: Boolean? = null,
)

private val suggestionJson = Json { ignoreUnknownKeys = true }

internal fun readVoicePreferenceSuggestion(raw: String?, capturedPackageName: String): VoicePreferenceSuggestion? = runCatching {
    val data = suggestionJson.parseToJsonElement(raw ?: return null).jsonObject
    // The serializer accepts quoted booleans; the capability contract does not.
    val marker = data["requiresConfirmation"]
    if (marker != null && marker != JsonPrimitive(true) && marker != JsonPrimitive(false)) return null
    val lockedMarker = data["scopeLocked"]
    if (lockedMarker != null && lockedMarker != JsonPrimitive(true) && lockedMarker != JsonPrimitive(false)) return null
    val payload = suggestionJson.decodeFromJsonElement<PreferenceSuggestionPayload>(data)
    if (payload.kind !in setOf("dictionary", "preference") || payload.text.length > 2000 ||
        utf8Bytes(payload.text) > VoicePreferenceLimits.RULE_BYTES || payload.scope !in setOf("app", "category", "all")) return null
    if (payload.scope == "category" && !validVoiceCategory(payload.categoryId)) return null
    if (payload.scope != "category" && payload.categoryId != null) return null
    if ((payload.scopeReason?.length ?: 0) > 1000 || (payload.name?.length ?: 0) > 180) return null
    // Ordinary corrections may offer an empty editor for the user to write a rule.
    // A memory confirmation must already contain the specific rule being confirmed.
    if (payload.requiresConfirmation && payload.text.isBlank()) return null
    if (payload.kind == "dictionary" && !validDictionaryWord(payload.text)) return null
    if (payload.kind == "preference" && capturedPackageName.isBlank()) return null
    if (payload.appIdentifier != null && payload.appIdentifier != capturedPackageName) return null
    if (payload.kind == "dictionary" && payload.scope == "category") return null
    if (payload.kind == "preference" && (payload.scopeLocked ?: payload.requiresConfirmation) && payload.scope != "app") return null
    VoicePreferenceSuggestion(payload.kind, payload.text, capturedPackageName, payload.scope, payload.requiresConfirmation,
        payload.categoryId, payload.scopeReason, payload.name, payload.scopeLocked)
}.getOrNull()

// Inspect the marker independently of draft validation. A malformed confirmation
// must not fall through to insertion just because its draft cannot be displayed.
internal fun voicePreferenceNoticeOnly(raw: String?, insertOnly: Boolean, finalText: String?, notice: String?): Boolean {
    val confirmation = runCatching {
        val marker = raw?.let { suggestionJson.parseToJsonElement(it).jsonObject["requiresConfirmation"] }
        marker != null && marker != JsonPrimitive(false)
    }.getOrDefault(false)
    return confirmation || (insertOnly && finalText == "" && !notice.isNullOrBlank())
}
