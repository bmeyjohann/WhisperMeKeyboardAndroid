package app.whisperme.keyboard.ime.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Latest server-reported budget survives closing the style dialog or restarting the app. */
class VoiceBudgetStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voice-budget", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(prefs.getString("last-status", null)?.let {
        runCatching { json.decodeFromString<VoiceBudgetStatus>(it) }.getOrNull()
    })
    val status = state.asStateFlow()
    @Synchronized fun record(budget: VoiceBudgetStatus) {
        prefs.edit().putString("last-status", json.encodeToString(budget)).apply()
        state.value = budget
    }
    companion object {
        @Volatile private var instance: VoiceBudgetStore? = null
        fun get(context: Context): VoiceBudgetStore = instance ?: synchronized(this) {
            instance ?: VoiceBudgetStore(context).also { instance = it }
        }
    }
}
