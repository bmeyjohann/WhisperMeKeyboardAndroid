package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Public readable mappings plus legacy storage-shaped responses. */
@Serializable
internal data class VoiceRuleMapping(
    val scope: String? = null,
    val categoryId: String? = null,
    val scopeName: String? = null,
    val appIdentifier: String? = null,
    val app_identifier: String? = null,
    val app_platform: String = "*",
) {
    fun preferenceScope(): VoicePreferenceScope? {
        val id = appIdentifier ?: app_identifier
        return when {
            scope == "category" -> VoicePreferenceScope("category", categoryId = categoryId)
            scope == "all" -> VoicePreferenceScope()
            scope == "app" && app_platform in setOf("android", "*") -> VoicePreferenceScope("app", id)
            id == "*" && app_platform == "*" -> VoicePreferenceScope()
            id?.startsWith("category:") == true && app_platform == "*" -> VoicePreferenceScope("category", categoryId = id.removePrefix("category:"))
            id != null && app_platform == "android" -> VoicePreferenceScope("app", id)
            else -> null
        }
    }

    fun label(appLabel: (String) -> String): String {
        val target = preferenceScope()
        if (target?.scope == "category") return "Category: ${voiceCategoryLabel(target.categoryId.orEmpty()).ifBlank { scopeName.orEmpty() }}"
        if (target?.scope == "all") return "All apps"
        val id = appIdentifier ?: app_identifier
        return if (!id.isNullOrBlank()) "${appLabel(id)} · $app_platform"
            else scopeName?.takeIf { it.isNotBlank() } ?: "Unassigned scope"
    }
}

internal fun readVoiceRuleMappings(raw: String): List<VoiceRuleMapping> = runCatching {
    Json { ignoreUnknownKeys = true }.decodeFromString<List<VoiceRuleMapping>>(raw)
}.getOrDefault(emptyList())
