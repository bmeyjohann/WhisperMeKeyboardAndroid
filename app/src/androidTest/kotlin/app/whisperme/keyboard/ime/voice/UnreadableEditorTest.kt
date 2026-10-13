package app.whisperme.keyboard.ime.voice

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.FlorisImeService
import app.whisperme.keyboard.keyboardManager
import app.whisperme.keyboard.editorInstance
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnreadableEditorTest {
    @Test fun insertsCompleteResultOnceWithoutReadingOrSelectingAndRejectsChangedField() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager by context.keyboardManager()
        val editor by context.editorInstance()
        fun fixture(method: String, extras: Bundle? = null) = context.contentResolver.call(
            android.net.Uri.parse("content://${context.packageName}.android-startup"), method, null, extras)!!
        fixture("launch", Bundle().apply { putBoolean("unreadableEditor", true) })
        try {
            SystemClock.sleep(1500)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, 200f, 200f, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (FlorisImeService.currentInputConnection() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            instrumentation.runOnMainSync { FlorisImeService.showUi() }
            SystemClock.sleep(1500)
            fixture("seed", Bundle().apply { putString("text", "Before after"); putInt("start", 7); putInt("end", 7) })
            SystemClock.sleep(700)
            var connection = checkNotNull(FlorisImeService.currentInputConnection())
            assertNull(connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0))
            assertNull(connection.getSurroundingText(4096, 4096, 0))
            assertNull(connection.getTextBeforeCursor(100, 0))
            assertNull(connection.getTextAfterCursor(100, 0))
            fun apply(result: String?, cursor: Int, changeField: Boolean = false, notice: String? = null) {
                instrumentation.runOnMainSync {
                    val snapshot = KeyboardManager::class.java.declaredMethods.first { it.name == "buildVoiceContext" }
                        .apply { isAccessible = true }.invoke(manager, editor.activeInfo, editor.activeContent, connection)
                    KeyboardManager::class.java.getDeclaredField("lastVoiceContext").apply { isAccessible = true }.set(manager, snapshot)
                    KeyboardManager::class.java.getDeclaredField("voiceInputConnection").apply { isAccessible = true }.set(manager, connection)
                    if (changeField) manager.onVoiceFieldChanged()
                    val type = KeyboardManager::class.java.declaredClasses.first { it.simpleName == "VoiceResponse" }
                    val response = type.declaredConstructors.first { it.parameterCount == 8 }.apply { isAccessible = true }
                        .newInstance(true, null, result, null, false, null, cursor, null)
                    type.getDeclaredField("notice").apply { isAccessible = true }.set(response, notice)
                    KeyboardManager::class.java.getDeclaredMethod("processVoiceResponse", type).apply { isAccessible = true }.invoke(manager, response)
                }
            }
            val complete = "new 😀\n".repeat(2000)
            apply(complete, complete.length)
            assertTrue(manager.voiceErrorMessage.value.orEmpty().contains("cannot confirm"))
            val expected = "Before " + complete + "after"
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(expected.toByteArray()).joinToString("") { "%02x".format(it) }
            val deadlineForCommit = SystemClock.elapsedRealtime() + 5000
            var inserted = fixture("snapshot")
            while (inserted.getString("sha256") != hash && SystemClock.elapsedRealtime() < deadlineForCommit) {
                SystemClock.sleep(50)
                inserted = fixture("snapshot")
            }
            assertEquals(hash, inserted.getString("sha256"))
            assertEquals(expected.length, inserted.getInt("length"))
            assertEquals(7 + complete.length, inserted.getInt("start"))
            assertEquals(inserted.getInt("start"), inserted.getInt("end"))
            assertEquals(1, inserted.getInt("commits"))
            assertEquals(0, inserted.getInt("selectionRequests"))
            assertEquals(complete, manager.voiceFinalText.value)
            assertFalse(manager.isVoiceRecording.value)
            assertFalse(manager.isVoiceProcessing.value)
            assertFalse(manager.isVoiceRetryable.value)
            instrumentation.runOnMainSync { manager.retryVoiceProcessing() }
            assertEquals(1, fixture("snapshot").getInt("commits"))
            apply("", 0, notice = "Preference saved for this app")
            assertEquals("Preference saved for this app", manager.voiceErrorMessage.value)
            assertFalse(manager.isVoiceRetryable.value)
            assertEquals(1, fixture("snapshot").getInt("commits"))
            apply(null, 0)
            assertEquals(1, fixture("snapshot").getInt("commits"))
            apply("Do not insert in another field", 0, changeField = true)
            assertTrue(manager.voiceErrorMessage.value.orEmpty().contains("text field changed"))
            assertEquals(hash, fixture("snapshot").getString("sha256"))
            assertEquals(1, fixture("snapshot").getInt("commits"))
        } finally {
            instrumentation.runOnMainSync { manager.cancelVoiceProcessing() }
            fixture("finish")
        }
    }
}
