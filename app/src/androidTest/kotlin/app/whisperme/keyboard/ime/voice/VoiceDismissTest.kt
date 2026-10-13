package app.whisperme.keyboard.ime.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.keyboardManager
import app.whisperme.keyboard.ime.ImeUiMode
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceDismissTest {
    @Test fun dismissRecoveryAlwaysLeavesVoiceAndCanBeRepeated() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager by instrumentation.targetContext.keyboardManager()
        instrumentation.runOnMainSync {
            val previous = KeyboardManager::class.java.getDeclaredField("previousUiModeBeforeVoice").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            fun setText(field: String, value: String?) {
                (KeyboardManager::class.java.getDeclaredField(field).apply { isAccessible = true }.get(manager) as MutableStateFlow<String?>).value = value
            }
            for (saved in listOf(null, ImeUiMode.VOICE_RECORDING, ImeUiMode.VOICE, ImeUiMode.TEXT)) {
                manager.activeState.imeUiMode = ImeUiMode.VOICE_RECORDING
                previous.set(manager, saved)
                setText("_voiceFinalText", "Already inserted text")
                setText("_voiceErrorMessage", "Insertion sent, but this app cannot confirm the text or cursor.")
                manager.cancelVoiceProcessing()
                assertEquals(ImeUiMode.TEXT, manager.activeState.imeUiMode)
                assertNull(manager.voiceFinalText.value)
                assertNull(manager.voiceErrorMessage.value)
                manager.cancelVoiceProcessing()
                assertEquals(ImeUiMode.TEXT, manager.activeState.imeUiMode)
            }
            previous.set(manager, ImeUiMode.TEXT)
            manager.activeState.imeUiMode = ImeUiMode.VOICE_RECORDING
            KeyboardManager::class.java.getDeclaredMethod("rememberUiModeBeforeVoice").apply { isAccessible = true }.invoke(manager)
            assertEquals(ImeUiMode.TEXT, previous.get(manager))
            manager.cancelVoiceProcessing()
        }
    }
}
