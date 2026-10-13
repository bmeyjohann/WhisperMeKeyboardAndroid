package app.whisperme.keyboard.app.settings.voiceinput

import android.content.Context
import app.whisperme.keyboard.app.auth.Auth0Manager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import app.whisperme.keyboard.ime.voice.*
import app.whisperme.keyboard.app.florisPreferenceModel

internal fun voiceRuleScopes(rule: JSONObject): List<VoicePreferenceScope> {
    val apps = rule.optJSONArray("apps")
    if (apps != null) return readVoiceRuleMappings(apps.toString()).mapNotNull { it.preferenceScope() }
    return listOf(when (rule.optString("scope")) {
        "category" -> VoicePreferenceScope("category", categoryId = rule.optString("categoryId"))
        "app" -> VoicePreferenceScope("app", rule.optString("app_identifier", rule.optString("appIdentifier")))
        "all" -> VoicePreferenceScope()
        else -> return emptyList()
    })
}

internal fun validateVoiceRuleBudgets(rules: List<JSONObject>, profiles: List<VoicePreferenceProfile>, categories: Map<String, String>) {
    requireVoiceRuleBudgets(rules.map { VoiceRuleBudgetEntry(it.getString("rule_text"), voiceRuleScopes(it)) }, profiles, categories)
}

internal fun voiceRuleBody(text: String, scope: VoicePreferenceScope, name: String? = null): JSONObject {
    VoicePreferenceLimits.requireRule(text)
    require(scope.valid()) { "Choose a valid writing rule scope." }
    return JSONObject().put("rule_text", text.trim()).put("scope", scope.scope)
        .apply {
            name?.takeIf { it.isNotBlank() }?.let { put("name", it.take(180)) }
            if (scope.scope == "category") put("categoryId", scope.categoryId)
            if (scope.scope == "app") put("app_identifier", scope.appIdentifier).put("app_platform", "android")
        }
}

/** Explicit management actions, independent of automatic preference use. No request/body logging. */
internal class VoicePreferenceApi(context: Context) {
    private val prefs by florisPreferenceModel()
    private val auth = Auth0Manager.getInstance(context)
    private val budgets = app.whisperme.keyboard.ime.voice.VoiceBudgetStore.get(context)
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val styleClient = client.newBuilder().callTimeout(160, TimeUnit.SECONDS).readTimeout(160, TimeUnit.SECONDS).build()
    suspend fun request(path: String, method: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        require(path in setOf("preferences", "style-profile"))
        if (path == "preferences" && method in setOf("POST", "PUT") && body?.has("rule_text") == true) {
            val text = body.getString("rule_text")
            VoicePreferenceLimits.requireRule(text)
            val existing = request("preferences", "GET").getJSONArray("rules")
            val others = (0 until existing.length()).map { existing.getJSONObject(it) }
                .filterNot { method == "PUT" && it.optInt("id", -1) == body.optInt("id", -2) }
            val updated = if (method == "PUT") {
                (0 until existing.length()).map { existing.getJSONObject(it) }
                    .find { it.optInt("id", -1) == body.optInt("id", -2) }
                    ?.let { JSONObject(it.toString()).put("rule_text", text) } ?: body
            } else body
            validateVoiceRuleBudgets(others + updated, readVoiceProfiles(prefs.voiceInput.preferenceProfiles.get()),
                readVoiceAppCategories(prefs.voiceInput.appCategories.get()))
        }
        var token = auth.getValidAccessToken() ?: error("Sign in to manage preferences, or retry if your connection is unavailable.")
        repeat(2) { attempt ->
            val request = Request.Builder().url("https://process-voice.whisperme.app/$path")
                .header("Authorization", "Bearer $token")
                .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
            (if (path == "style-profile") styleClient else client).newCall(request).execute().use { response ->
                if (response.code == 401 && attempt == 0) {
                    token = auth.getValidAccessToken(forceRefresh = true) ?: error("Session could not be renewed. Sign in or retry your connection.")
                } else {
                    if (response.code == 401) auth.requireSignIn()
                    val raw = response.body?.string().orEmpty().ifBlank { "{}" }
                    app.whisperme.keyboard.ime.voice.parseVoiceBudgetStatus(raw)?.let(budgets::record)
                    val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                    check(response.isSuccessful) {
                        result.optString("error").takeIf { it.isNotBlank() }
                            ?: "Preference request failed (${response.code}). ${if (response.code == 401) "Please sign in." else "Retry when the service is available."}"
                    }
                    return@withContext result
                }
            }
        }
        error("Session validation failed")
    }
}
