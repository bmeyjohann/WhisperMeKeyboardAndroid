package app.whisperme.keyboard.app.auth

import android.content.Context
import app.whisperme.keyboard.BuildConfig
import org.json.JSONObject
import java.io.File

/** Provision only through adb run-as into a debug app's private storage. */
object LocalVoiceSession {
    const val ENDPOINT = "ws://127.0.0.1:8877/realtime"
    fun configured(context: Context): Boolean =
        BuildConfig.DEBUG && File(context.filesDir, "local-voice-session.json").isFile

    fun token(context: Context): String? {
        if (!configured(context)) return null
        val json = JSONObject(File(context.filesDir, "local-voice-session.json").readText())
        val token = json.getString("token")
        require(token.length >= 32 && json.getLong("expires_at") > System.currentTimeMillis()) {
            "Local test session expired; restart the local launcher"
        }
        return token
    }
    fun active(context: Context): Boolean = runCatching { token(context) != null }.getOrDefault(false)
}
