package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class VoiceDiagnosticEntry(val kind: String, val stage: String, val data: JsonElement)

@Serializable
data class VoiceDiagnostics(
    val version: Int = 1,
    val truncated: Boolean,
    val entries: List<VoiceDiagnosticEntry>,
    val contextSources: JsonElement? = null,
)

/** Consume only the explicit diagnostic envelope; never log requests, headers or audio buffers. */
internal fun parseVoiceDiagnostics(raw: String?, enabled: Boolean): VoiceDiagnostics? {
    if (!enabled || raw == null) return null
    return runCatching {
        if (raw.length > 600_000) return VoiceDiagnostics(truncated = true, entries = emptyList())
        val envelope = Json.parseToJsonElement(raw).jsonObject
        if (envelope["version"]?.jsonPrimitive?.intOrNull != 1) return null
        val entries = envelope["entries"]?.jsonArray ?: return null
        var truncated = envelope["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
        var remaining = 512_000
        val sensitive = Regex("(?i)authorization|cookie|token|secret|password|credential|api.?key|audio|base64")
        val bearer = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*")
        fun clean(value: JsonElement, depth: Int = 0): JsonElement {
            if (depth > 32 || remaining <= 0) { truncated = true; return JsonPrimitive("[Truncated]") }
            return when (value) {
                is JsonObject -> JsonObject(value.entries.associate { (key, child) ->
                    key to if (sensitive.containsMatchIn(key)) JsonPrimitive("[Redacted]") else clean(child, depth + 1)
                })
                is JsonArray -> JsonArray(value.map {
                    clean(it, depth + 1)
                })
                is JsonPrimitive -> if (value.isString) {
                    val embedded = value.content.trimStart().takeIf { it.startsWith("{") || it.startsWith("[") }
                        ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                    if (embedded != null) {
                        val sanitized = clean(embedded, depth + 1).toString()
                        if (sanitized.length > 160_000) truncated = true
                        return JsonPrimitive(sanitized.take(160_000))
                    }
                    val text = value.content.replace(bearer, "[Redacted]")
                    val kept = text.take(minOf(remaining, 160_000))
                    if (kept.length != text.length) truncated = true
                    remaining -= kept.length
                    JsonPrimitive(kept)
                } else value
            }
        }
        val sources = envelope["contextSources"]?.let { clean(it) }
        val sanitized = entries.map { element ->
            val entry = element.jsonObject
            VoiceDiagnosticEntry(
                clean(entry.getValue("kind")).jsonPrimitive.content,
                clean(entry.getValue("stage")).jsonPrimitive.content,
                clean(entry.getValue("data")),
            )
        }
        var result = VoiceDiagnostics(truncated = truncated, entries = sanitized, contextSources = sources)
        // Include JSON structure in the hard session cap, not only string payloads.
        while (Json.encodeToString(VoiceDiagnostics.serializer(), result).length > 512_000 && result.entries.isNotEmpty()) {
            result = result.copy(truncated = true, entries = result.entries.dropLast(1))
        }
        if (Json.encodeToString(VoiceDiagnostics.serializer(), result).length > 512_000)
            result.copy(truncated = true, contextSources = JsonPrimitive("[Truncated]")) else result
    }.getOrNull()
}
