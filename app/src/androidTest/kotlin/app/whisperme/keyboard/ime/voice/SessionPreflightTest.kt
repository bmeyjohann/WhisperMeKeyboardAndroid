package app.whisperme.keyboard.ime.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.app.auth.Auth0Manager
import app.whisperme.keyboard.app.auth.LocalVoiceSession
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import app.whisperme.keyboard.keyboardManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only in the owned signed-out emulator, never against a user's account. */
@RunWith(AndroidJUnit4::class)
class SessionPreflightTest {
    @Test fun signedOutStartupIsVisibleBeforeRecordingAndCannotOpenMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Use the isolated non-local-auth fixture", LocalVoiceSession.configured(context))
        val auth = Auth0Manager.getInstance(context)
        auth.checkSessionBeforeUse()
        val deadline = SystemClock.elapsedRealtime() + 16000
        while (auth.sessionStatus.value == Auth0Manager.SessionStatus.CHECKING && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertEquals(Auth0Manager.SessionStatus.SIGN_IN_REQUIRED, auth.sessionStatus.value)
        val manager by context.keyboardManager()
        instrumentation.runOnMainSync {
            KeyboardManager::class.java.getDeclaredMethod("startVoiceRecording").apply { isAccessible = true }.invoke(manager)
        }
        val errorDeadline = SystemClock.elapsedRealtime() + 16000
        while (manager.voiceErrorMessage.value == null && SystemClock.elapsedRealtime() < errorDeadline) SystemClock.sleep(20)
        assertFalse(manager.isVoiceRecording.value)
        assertTrue(manager.voiceErrorMessage.value.orEmpty().contains("No audio was recorded"))
        for (field in listOf("mediaRecorder", "audioRecord")) {
            val member = KeyboardManager::class.java.getDeclaredField(field).apply { isAccessible = true }
            assertNull("No recorder may be opened", member.get(manager))
        }
    }
}
