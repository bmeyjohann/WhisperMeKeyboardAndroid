package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class VoiceStyleDraft(
    val instructions: String,
    val name: String? = null,
    val scope: String? = null,
    val categoryId: String? = null,
    val appIdentifier: String? = null,
    val scopeReason: String? = null,
) {
    fun resolvedScope(currentApp: String?, fallback: VoicePreferenceScope): VoicePreferenceScope {
        VoicePreferenceLimits.requireRule(instructions)
        require((name?.length ?: 0) <= 180 && (scopeReason?.length ?: 0) <= 1000) { "Style draft metadata is too long." }
        if (scope == null) {
            require(categoryId == null && appIdentifier == null) { "Style response has no scope for its target." }
            return fallback
        }
        val result = when (scope) {
            "all" -> VoicePreferenceScope(scope, appIdentifier, categoryId)
            "category" -> VoicePreferenceScope(scope, categoryId = categoryId).also {
                require(appIdentifier == null || appIdentifier == currentApp) { "Style response targets a different app." }
            }
            "app" -> VoicePreferenceScope(scope, appIdentifier ?: currentApp, categoryId)
            else -> error("Style response has an unknown scope. Existing draft preserved.")
        }
        require(result.valid() && (result.scope != "app" || result.appIdentifier == currentApp)) {
            "Style response has an unknown category or different app. Existing draft preserved."
        }
        return result
    }
}

internal fun readVoiceStyleDraft(raw: String): VoiceStyleDraft =
    Json { ignoreUnknownKeys = true }.decodeFromString(raw)
