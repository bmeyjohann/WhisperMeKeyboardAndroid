package app.whisperme.keyboard.ime.voice

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.FlorisImeService
import app.whisperme.keyboard.app.florisPreferenceModel
import app.whisperme.keyboard.app.settings.voiceinput.VoiceTranscriptionProvider
import app.whisperme.keyboard.ime.ImeUiMode
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import app.whisperme.keyboard.keyboardManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceCanvasTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private class Socket : okhttp3.WebSocket {
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun request() = okhttp3.Request.Builder().url("ws://127.0.0.1/fixture").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages += text; return true }
        override fun send(bytes: okio.ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }
    private fun fixture(method: String) = context.contentResolver.call(
        android.net.Uri.parse("content://${context.packageName}.android-startup"), method, null, null)
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.uiAutomation.windows.forEach { window -> find(window.root, predicate)?.let { return it } }
            SystemClock.sleep(50)
        }
        val dump = StringBuilder()
        fun describe(n: AccessibilityNodeInfo?) {
            if (n == null) return
            dump.append("\ntext=${n.text} desc=${n.contentDescription} actions=${n.actionList}")
            for (i in 0 until n.childCount) describe(n.getChild(i))
        }
        instrumentation.uiAutomation.windows.forEach { describe(it.root) }
        java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "voice-canvas-failure.png")).use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        error("Voice canvas accessibility node not found: $dump")
    }
    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }
    @Test fun canvasFinishesAndCancelDoesNotBubble() {
        val manager by context.keyboardManager()
        val prefs by florisPreferenceModel()
        val oldProvider = prefs.voiceInput.transcriptionProvider.get()
        val service = instrumentation.uiAutomation.serviceInfo
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = service
        fixture("launch")
        val editor = node { it.className?.toString() == "android.widget.EditText" }
        val editorBounds = Rect().also { editor.getBoundsInScreen(it) }
        tap(editorBounds.exactCenterX(), editorBounds.top + 40f)
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (FlorisImeService.currentInputConnection() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertNotNull(FlorisImeService.currentInputConnection())
        SystemClock.sleep(1200) // Let the fixture's delayed showSoftInput finish before changing modes.
        instrumentation.runOnMainSync { FlorisImeService.showUi() }
        SystemClock.sleep(1500)
        val original = fixture("snapshot")!!.getString("sha256")
        try {
            prefs.voiceInput.transcriptionProvider.set(VoiceTranscriptionProvider.MUSE_REALTIME)
            repeat(4) { attempt ->
                val socket = Socket()
                val response = CompletableDeferred<Any>()
                instrumentation.runOnMainSync {
                    manager.cancelVoiceProcessing()
                    fun set(name: String, value: Any) = KeyboardManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
                    set("realtimeWebSocket", socket)
                    set("realtimeResponseDeferred", response)
                    @Suppress("UNCHECKED_CAST")
                    val recording = KeyboardManager::class.java.getDeclaredField("_isVoiceRecording").apply { isAccessible = true }.get(manager) as MutableStateFlow<Boolean>
                    recording.value = true
                    manager.activeState.batchEdit { it.imeUiMode = ImeUiMode.VOICE_RECORDING }
                }
                val canvas = node { n -> n.actionList.any { it.label?.toString() == "Finish recording and insert" } }
                val bounds = Rect().also { canvas.getBoundsInScreen(it) }
                if (attempt == 3) {
                    val cancel = node { it.contentDescription?.toString() == "Cancel recording without inserting" }
                    val rect = Rect().also { cancel.getBoundsInScreen(it) }
                    tap(rect.exactCenterX(), rect.exactCenterY())
                } else {
                    if (attempt == 0) java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "voice-canvas.png")).use {
                        instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val x = if (attempt == 0) bounds.left + 12f else bounds.exactCenterX()
                    val y = if (attempt == 0) bounds.top + 12f else if (attempt == 1) bounds.top + bounds.height() * .4f else bounds.bottom - 5f
                    tap(x, y)
                }
                val stopped = SystemClock.elapsedRealtime() + 3000
                while (manager.isVoiceRecording.value && SystemClock.elapsedRealtime() < stopped) SystemClock.sleep(20)
                assertFalse("Tap must end recording", manager.isVoiceRecording.value)
                if (attempt == 3) {
                    assertFalse("Cancel must not start processing", manager.isVoiceProcessing.value)
                    assertTrue("Cancel must not send session.stop", socket.messages.isEmpty())
                } else {
                    assertTrue("Canvas must enter processing", manager.isVoiceProcessing.value)
                    assertEquals(1, socket.messages.count { it.contains("session.stop") })
                }
                assertEquals("No premature insertion", original, fixture("snapshot")!!.getString("sha256"))
                instrumentation.runOnMainSync { manager.cancelVoiceProcessing() }
                response.cancel()
            }
        } finally {
            instrumentation.runOnMainSync { manager.cancelVoiceProcessing() }
            prefs.voiceInput.transcriptionProvider.set(oldProvider)
            fixture("finish")
        }
    }
}
