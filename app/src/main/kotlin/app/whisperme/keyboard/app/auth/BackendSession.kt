package app.whisperme.keyboard.app.auth

internal sealed interface BackendSession {
    data class Accepted(val token: String) : BackendSession
    data object Rejected : BackendSession
    data object Retry : BackendSession
    data object NoToken : BackendSession
}

/** Only a 401 warrants one forced refresh. Transport failures propagate without losing credentials. */
internal suspend fun validateBackendSession(
    token: suspend (Boolean) -> String?,
    accepts: suspend (String) -> Int,
): BackendSession {
    var candidate = token(false) ?: return BackendSession.NoToken
    var status = accepts(candidate)
    if (status == 401) {
        candidate = token(true) ?: return BackendSession.NoToken
        status = accepts(candidate)
    }
    return when (status) {
        in 200..299 -> BackendSession.Accepted(candidate)
        401 -> BackendSession.Rejected
        else -> BackendSession.Retry
    }
}

internal fun backendAllowsInsertOnly(body: String): Boolean = runCatching {
    val root = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
    val features = root?.get("features") as? kotlinx.serialization.json.JsonObject
    features?.get("insertOnly") == kotlinx.serialization.json.JsonPrimitive(true)
}.getOrDefault(false)
