package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class VoiceAppCategory(val id: String, val label: String)

/** Stable protocol IDs. Apps belong to a category only when the user assigns them. */
val voiceAppCategories = listOf(
    VoiceAppCategory("personal", "Personal/social"),
    VoiceAppCategory("business", "Business/work"),
    VoiceAppCategory("development", "Development"),
    VoiceAppCategory("writing", "Writing/publishing"),
    VoiceAppCategory("productivity", "Notes/planning"),
    VoiceAppCategory("education", "Study/learning"),
    VoiceAppCategory("other", "Other"),
)

fun validVoiceCategory(id: String?): Boolean = voiceAppCategories.any { it.id == id }
fun voiceCategoryLabel(id: String): String = voiceAppCategories.find { it.id == id }?.label ?: id

internal fun readVoiceAppCategories(raw: String): Map<String, String> = runCatching {
    Json.decodeFromString<Map<String, String>>(raw).filter { (app, category) ->
        app.isNotBlank() && !app.startsWith("category:") && app != "*" && validVoiceCategory(category)
    }
}.getOrDefault(emptyMap())

@Serializable
data class VoicePreferenceScope(
    val scope: String = "all",
    val appIdentifier: String? = null,
    val categoryId: String? = null,
) {
    fun valid(): Boolean = when (scope) {
        "all" -> appIdentifier == null && categoryId == null
        "app" -> !appIdentifier.isNullOrBlank() && appIdentifier != "*" &&
            !appIdentifier.startsWith("category:") && categoryId == null
        "category" -> validVoiceCategory(categoryId) && appIdentifier == null
        else -> false
    }

    fun matches(app: String, category: String?): Boolean = valid() && when (scope) {
        "app" -> appIdentifier == app
        "category" -> categoryId == category
        else -> true
    }

    fun label(appLabel: (String) -> String = { it }): String = when (scope) {
        "app" -> "App: ${appLabel(appIdentifier.orEmpty())}"
        "category" -> "Category: ${voiceCategoryLabel(categoryId.orEmpty())}"
        else -> "All apps"
    }
}

internal fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

internal object VoicePreferenceLimits {
    const val DICTIONARY_BYTES = 4096
    const val RULE_BYTES = 8000
    const val ACTIVE_BYTES = 16000
    const val SAVED_BYTES = 64000
    const val SAVED_COUNT = 100

    fun validRule(text: String): Boolean = text.isNotBlank() && text.length <= 2000 && utf8Bytes(text) <= RULE_BYTES

    fun requireRule(text: String) = require(validRule(text)) {
        "Use a writing rule of up to 2,000 characters and 8,000 UTF-8 bytes."
    }

    fun requireSaved(rules: List<String>) {
        require(rules.size <= SAVED_COUNT) { "Up to 100 saved rules. Remove a rule before adding another." }
        require(rules.sumOf(::utf8Bytes) <= SAVED_BYTES) { "Saved rules exceed 64,000 UTF-8 bytes. Shorten or remove a rule." }
    }

    fun requireActive(rules: List<String>) {
        require(rules.sumOf(::utf8Bytes) <= ACTIVE_BYTES) { "Active rules exceed 16,000 UTF-8 bytes (about 4,000 tokens). Shorten the rules for this scope." }
    }

    fun requireSamples(samples: List<String>) {
        require(samples.isNotEmpty() && samples.size <= 5 && samples.all { it.isNotBlank() } &&
            samples.sumOf { it.length } <= 12000) { "Provide 1–5 writing examples, up to 12,000 characters in total." }
    }
}

internal data class VoiceRuleBudgetEntry(val text: String, val scopes: List<VoicePreferenceScope>)

/** Evaluate every explicit app and category, plus an unassigned app for global rules. */
internal fun requireVoiceRuleBudgets(cloud: List<VoiceRuleBudgetEntry>, profiles: List<VoicePreferenceProfile>, categories: Map<String, String>) {
    VoicePreferenceLimits.requireSaved(cloud.map { it.text } + profiles.map { it.instructions })
    val apps = categories.keys + profiles.flatMap { it.packages } + cloud.flatMap { it.scopes.mapNotNull { scope -> scope.appIdentifier } }
    val targets = apps.map { it to categories[it] } + voiceAppCategories.map { "" to it.id } + listOf("" to null)
    targets.forEach { (app, category) ->
        val active = cloud.filter { it.scopes.any { scope -> scope.matches(app, category) } }.map { it.text }
        val profile = selectVoiceProfile(profiles, app, category)
        VoicePreferenceLimits.requireActive(active + listOfNotNull(profile?.instructions))
    }
}
