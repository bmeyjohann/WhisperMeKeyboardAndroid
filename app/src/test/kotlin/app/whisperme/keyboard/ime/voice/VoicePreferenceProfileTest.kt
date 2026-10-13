package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoicePreferenceProfileTest {
    private val profiles = listOf(
        VoicePreferenceProfile("1", "General", "General style"),
        VoicePreferenceProfile("2", "Chat", "Chat style", "category", listOf("com.chatgpt", "com.chat")),
        VoicePreferenceProfile("3", "ChatGPT", "Specific style", "app", listOf("com.chatgpt")),
    )
    @Test fun explicitMappingAndSpecificity() {
        val raw = Json.encodeToString(profiles)
        assertEquals("3", selectVoiceProfile(raw, true, "com.chatgpt")?.id)
        assertEquals("2", selectVoiceProfile(raw, true, "com.chat")?.id)
        assertEquals("1", selectVoiceProfile(raw, true, "com.chatgpt.fake")?.id)
        assertNull(selectVoiceProfile(raw, false, "com.chatgpt"))
    }
    @Test fun invalidProfilesCannotReachWorker() {
        val invalid = listOf(profiles[0].copy(instructions = "x".repeat(2001)),
            profiles[1].copy(packages = emptyList()), profiles[2].copy(packages = listOf("a", "b")))
        assertNull(selectVoiceProfile(Json.encodeToString(invalid), true, "com.chatgpt"))
        assertNull(selectVoiceProfile("broken", true, "com.chatgpt"))
    }
}
