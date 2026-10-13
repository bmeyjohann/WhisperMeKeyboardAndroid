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
class LimitedEditorTest {
    @Test fun insertsWithoutFullTextOrSelectionSupportButRejectsEdits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager by context.keyboardManager()
        val editor by context.editorInstance()
        fun fixture(method: String, extras: Bundle? = null) = context.contentResolver.call(
            android.net.Uri.parse("content://${context.packageName}.android-startup"), method, null, extras)!!
        fixture("launch", Bundle().apply { putBoolean("limitedEditor", true) })
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
            val ready = SystemClock.elapsedRealtime() + 5000
            while (connection.getSurroundingText(4096, 4096, 0) == null && SystemClock.elapsedRealtime() < ready) {
                SystemClock.sleep(100)
                connection = checkNotNull(FlorisImeService.currentInputConnection())
            }
            assertNull(connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0))
            // Android's remote wrapper acknowledges queuing even when the editor refuses it.
            connection.setSelection(0, 0)
            SystemClock.sleep(200)
            assertEquals(7, fixture("snapshot").getInt("start"))
            fun apply(result: String, cursor: Int, changeField: Boolean = false) {
                instrumentation.runOnMainSync {
                    val snapshot = KeyboardManager::class.java.declaredMethods.first { it.name == "buildVoiceContext" }
                        .apply { isAccessible = true }.invoke(manager, editor.activeInfo, editor.activeContent, connection)
                    KeyboardManager::class.java.getDeclaredField("lastVoiceContext").apply { isAccessible = true }.set(manager, snapshot)
                    KeyboardManager::class.java.getDeclaredField("voiceInputConnection").apply { isAccessible = true }.set(manager, connection)
                    if (changeField) manager.onVoiceFieldChanged()
                    val type = KeyboardManager::class.java.declaredClasses.first { it.simpleName == "VoiceResponse" }
                    val response = type.declaredConstructors.first { it.parameterCount == 8 }.apply { isAccessible = true }
                        .newInstance(true, null, result, null, false, null, cursor, null)
                    KeyboardManager::class.java.getDeclaredMethod("processVoiceResponse", type).apply { isAccessible = true }.invoke(manager, response)
                }
            }
            apply("Before new after", 11)
            assertNull(manager.voiceErrorMessage.value)
            val inserted = fixture("snapshot")
            assertEquals(16, inserted.getInt("length"))
            val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("Before new after".toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(expectedHash, inserted.getString("sha256"))
            assertEquals(11, inserted.getInt("start"))
            apply("Before new extra after", 17, changeField = true)
            assertTrue(manager.voiceErrorMessage.value.orEmpty().contains("text field changed"))
            assertEquals(inserted.getString("sha256"), fixture("snapshot").getString("sha256"))
            apply("Replace everything", 18)
            assertTrue(manager.voiceErrorMessage.value.orEmpty().contains("changes existing text"))
            assertEquals(inserted.getString("sha256"), fixture("snapshot").getString("sha256"))
        } finally {
            instrumentation.runOnMainSync { manager.cancelVoiceProcessing() }
            fixture("finish")
        }
    }
}
