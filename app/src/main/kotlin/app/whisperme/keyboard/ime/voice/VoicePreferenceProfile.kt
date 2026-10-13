package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class VoicePreferenceProfile(
    val id: String,
    val name: String,
    val instructions: String,
    val kind: String = "all",
    val packages: List<String> = emptyList(),
    val categoryId: String? = null,
) {
    val scope: String get() = when (kind) {
        "app" -> "app:${packages.singleOrNull().orEmpty()}"
        "category" -> "category:${categoryId ?: name}"
        else -> "all"
    }
    fun valid(): Boolean = name.isNotBlank() && VoicePreferenceLimits.validRule(instructions) &&
        scope.length <= 500 && kind in setOf("all", "app", "category") &&
        (kind == "all" || (kind == "category" && validVoiceCategory(categoryId)) ||
            (packages.isNotEmpty() && packages.all { it.isNotBlank() })) &&
        (categoryId == null || kind == "category") &&
        (kind != "app" || packages.size == 1)
}

internal fun readVoiceProfiles(raw: String): List<VoicePreferenceProfile> =
    runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<VoicePreferenceProfile>>(raw) }.getOrDefault(emptyList())

internal fun updateVoiceProfile(raw: String, profile: VoicePreferenceProfile): String {
    val json = Json { encodeDefaults = true }
    val entries = json.parseToJsonElement(raw) as? JsonArray
        ?: error("Stored writing styles could not be read. Existing data has been preserved.")
    val original = entries.find { (it as? JsonObject)?.get("id") == JsonPrimitive(profile.id) } as? JsonObject
    val replacement = JsonObject(original.orEmpty() + (json.encodeToJsonElement(profile) as JsonObject))
    return JsonArray(entries.filterNot { (it as? JsonObject)?.get("id") == JsonPrimitive(profile.id) } + replacement).toString()
}

internal fun removeVoiceProfile(raw: String, id: String): String {
    val entries = Json.parseToJsonElement(raw) as? JsonArray
        ?: error("Stored writing styles could not be read. Existing data has been preserved.")
    return JsonArray(entries.filterNot { (it as? JsonObject)?.get("id") == JsonPrimitive(id) }).toString()
}

/** Explicit package mappings only. Most specific matching profile wins. */
internal fun selectVoiceProfile(raw: String, enabled: Boolean, packageName: String, appCategory: String? = null): VoicePreferenceProfile? {
    if (!enabled) return null
    return selectVoiceProfile(readVoiceProfiles(raw), packageName, appCategory)
}

internal fun selectVoiceProfile(profiles: List<VoicePreferenceProfile>, packageName: String, appCategory: String?): VoicePreferenceProfile? {
    return profiles.filter { it.valid() && (it.kind == "all" ||
        (it.kind == "category" && it.categoryId != null && it.categoryId == appCategory) ||
        (!validVoiceCategory(it.categoryId) && packageName in it.packages)) }
        .maxWithOrNull(compareBy<VoicePreferenceProfile> { when (it.kind) { "app" -> 3; "category" -> 2; else -> 1 } }
            .thenBy { profiles.indexOf(it) }) // Most recently saved style wins a same-scope tie without deleting older data.
}
