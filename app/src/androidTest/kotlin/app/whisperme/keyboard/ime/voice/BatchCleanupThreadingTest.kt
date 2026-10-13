package app.whisperme.keyboard.ime.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.keyboardManager
import app.whisperme.keyboard.ime.ImeUiMode
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BatchCleanupThreadingTest {
    @Test fun cancelledBackgroundCleanupRestoresKeyboardAndPreservesNewerRecording() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager by instrumentation.targetContext.keyboardManager()
        val recording = KeyboardManager::class.java.getDeclaredField("voiceRecordingFile").apply { isAccessible = true }
        val previous = KeyboardManager::class.java.getDeclaredField("previousUiModeBeforeVoice").apply { isAccessible = true }
        val oldFile = File(instrumentation.targetContext.cacheDir, "thread-test-old")
        val newFile = File(instrumentation.targetContext.cacheDir, "thread-test-new")
        fun prepare(file: File) = instrumentation.runOnMainSync {
            manager.cancelVoiceProcessing()
            manager.activeState.imeUiMode = ImeUiMode.VOICE_RECORDING
            previous.set(manager, ImeUiMode.TEXT)
            recording.set(manager, file)
        }
        try {
            prepare(oldFile)
            launch(Dispatchers.IO) {
                coroutineContext.cancel()
                manager.finishBatchRecordingUi(oldFile)
            }.join()
            instrumentation.runOnMainSync {
                assertEquals(ImeUiMode.TEXT, manager.activeState.imeUiMode)
                assertNull(recording.get(manager))
            }
            prepare(newFile)
            launch(Dispatchers.IO) { manager.finishBatchRecordingUi(oldFile) }.join()
            instrumentation.runOnMainSync {
                assertEquals(ImeUiMode.VOICE_RECORDING, manager.activeState.imeUiMode)
                assertSame(newFile, recording.get(manager))
            }
        } finally {
            instrumentation.runOnMainSync { manager.cancelVoiceProcessing() }
        }
    }
}
