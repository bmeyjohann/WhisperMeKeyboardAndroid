package app.whisperme.keyboard.ime.voice

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.app.auth.LocalVoiceSession
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import app.whisperme.keyboard.FlorisImeService
import app.whisperme.keyboard.keyboardManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidStartupTest {
    private class CapturingSocket : okhttp3.WebSocket {
        val messages = mutableListOf<String>()
        override fun request() = okhttp3.Request.Builder().url("ws://127.0.0.1:8877/realtime").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages += text; return true }
        override fun send(bytes: okio.ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(method: String, extras: Bundle? = null): Bundle = checkNotNull(context.contentResolver.call(
        android.net.Uri.parse("content://${context.packageName}.android-startup"), method, null, extras))
    private fun seed(text: String, start: Int = text.length, end: Int = start) = fixture("seed", Bundle().apply {
        putString("text", text); putInt("start", start); putInt("end", end)
    })
    private fun assertFixtureText(expected: String) {
        val snapshot = fixture("snapshot")
        assertEquals(expected.length, snapshot.getInt("length"))
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(hash, snapshot.getString("sha256"))
    }
    @Test fun nativeLargeFieldReassemblyCaretAndChangedFieldGuard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(LocalVoiceSession.active(context))
        fixture("launch")
        val manager by context.keyboardManager()
        val original = "aaaaaaaaaaaaaaaaaaa\n".repeat(4000) + "chosen" + "bbbbbbbbbbbbbbbbbbb\n".repeat(4000)
        try {
            SystemClock.sleep(1000)
            repeat(4) { attempt ->
                val selectionStart = when (attempt) { 2 -> original.length; 3 -> 0; else -> 80_000 }
                val selectionEnd = if (attempt < 2) 80_006 else selectionStart
                seed(original, selectionStart, selectionEnd)
                SystemClock.sleep(500)
                instrumentation.runOnMainSync {
                    KeyboardManager::class.java.getDeclaredMethod("startVoiceRecording").apply { isAccessible = true }.invoke(manager)
                }
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!manager.isVoiceRecording.value && manager.voiceErrorMessage.value == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                assertTrue("Full native snapshot failed: ${manager.voiceErrorMessage.value}", manager.isVoiceRecording.value)
                val snapshotField = KeyboardManager::class.java.getDeclaredField("lastVoiceContext").apply { isAccessible = true }
                val connectionField = KeyboardManager::class.java.getDeclaredField("voiceInputConnection").apply { isAccessible = true }
                val snapshot = snapshotField.get(manager)
                val connection = connectionField.get(manager)
                instrumentation.runOnMainSync { manager.cancelVoiceProcessing() }
                SystemClock.sleep(300)
                if (attempt == 1) seed(original + "changed", 80_000, 80_006)
                val before = if (attempt == 3) 0 else 12_000
                val after = when (attempt) { 2 -> 0; 3 -> 12_000; else -> 6_000 }
                val expectedStart = (selectionStart - before).coerceAtLeast(0)
                val expectedEnd = (selectionEnd + after).coerceAtMost(original.length)
                instrumentation.runOnMainSync {
                    snapshotField.set(manager, snapshot)
                    connectionField.set(manager, connection)
                    val socket = CapturingSocket()
                    KeyboardManager::class.java.getDeclaredField("realtimeWebSocket").apply { isAccessible = true }.set(manager, socket)
                    val deferred = kotlinx.coroutines.CompletableDeferred<Any>()
                    val handler = KeyboardManager::class.java.getDeclaredMethod("handleRealtimeWebSocketMessage",
                        String::class.java, kotlinx.coroutines.CompletableDeferred::class.java).apply { isAccessible = true }
                    handler.invoke(manager, """{"type":"context.request","requestId":"native-window-$attempt","beforeChars":$before,"afterChars":$after,"reason":"Need earlier synthetic facts"}""", deferred)
                    assertFalse("The real worker context request shape must be accepted", deferred.isCompleted)
                    val provided = org.json.JSONObject(socket.messages.single())
                    assertEquals("context.provide", provided.getString("type"))
                    assertEquals("native-window-$attempt", provided.getString("requestId"))
                    val expanded = provided.getJSONObject("context")
                    assertEquals(original.substring(expectedStart, expectedEnd), expanded.getString("fullText"))
                    assertEquals(expectedStart, expanded.getJSONObject("contextWindow").getInt("start"))
                    assertEquals(original.length, expanded.getJSONObject("contextWindow").getInt("totalLength"))
                    val contextMethod = KeyboardManager::class.java.declaredMethods.first { it.name == "buildVoiceContextJson" }
                        .apply { isAccessible = true }
                    val initial = org.json.JSONObject(contextMethod.invoke(manager, snapshot, 2000, 2000) as String)
                    for (key in listOf("packageName", "fieldType", "fieldRole", "fieldHint", "fieldLabel", "fieldName", "isMultiline", "keyboardMode", "locale", "imeOptions")) {
                        assertEquals("Expansion metadata changed: $key", initial.get(key).toString(), expanded.get(key).toString())
                    }
                    val responseClass = KeyboardManager::class.java.declaredClasses.first { it.simpleName == "VoiceResponse" }
                    val response = responseClass.declaredConstructors.first { it.parameterCount == 8 }.apply { isAccessible = true }
                        .newInstance(true, null, "replacement", null, false, null, 4, expanded.getJSONObject("contextWindow").toString())
                    KeyboardManager::class.java.getDeclaredMethod("processVoiceResponse", responseClass)
                        .apply { isAccessible = true }.invoke(manager, response)
                }
                if (attempt != 1) {
                    val expected = original.take(expectedStart) + "replacement" + original.substring(expectedEnd)
                    assertFixtureText(expected)
                    assertEquals(expectedStart + 4, fixture("snapshot").getInt("start"))
                    assertEquals(expectedStart + 4, fixture("snapshot").getInt("end"))
                    if (attempt == 0) {
                        assertEquals("1:10:1", fixture("snapshot").getString("bold"))
                        assertEquals("${expected.length - 15}:${expected.length - 5}:https://example.invalid/fixture", fixture("snapshot").getString("links"))
                    }
                } else {
                    assertFixtureText(original + "changed")
                    assertTrue("Changed field must fail closed: ${manager.voiceErrorMessage.value}",
                        manager.voiceErrorMessage.value?.contains("not inserted") == true)
                }
            }
        } finally {
            instrumentation.runOnMainSync { manager.cancelVoiceProcessing() }
            fixture("finish")
        }
    }

    @Test fun nativeImeStartupAndCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Provision the isolated local session first", LocalVoiceSession.active(context))
        fixture("launch")
        val manager by context.keyboardManager()
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (FlorisImeService.currentInputConnection() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertNotNull("WhisperMe must be the selected IME", FlorisImeService.currentInputConnection())
            SystemClock.sleep(500)
            val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            assertTrue("Preparing allocation must not record audio", audio.activeRecordingConfigurations.isEmpty())
            repeat(4) {
                instrumentation.runOnMainSync {
                    KeyboardManager::class.java.getDeclaredMethod("startVoiceRecording").apply { isAccessible = true }.invoke(manager)
                }
                val recordingDeadline = SystemClock.elapsedRealtime() + 5_000
                while (!manager.isVoiceRecording.value && manager.voiceErrorMessage.value == null &&
                    SystemClock.elapsedRealtime() < recordingDeadline) SystemClock.sleep(20)
                assertTrue("Microphone startup failed: ${manager.voiceErrorMessage.value}", manager.isVoiceRecording.value)
                if (it == 0) {
                    val deferred = KeyboardManager::class.java.getDeclaredField("realtimeResponseDeferred")
                        .apply { isAccessible = true }.get(manager)
                    val handler = KeyboardManager::class.java.getDeclaredMethod("handleRealtimeWebSocketMessage",
                        String::class.java, kotlinx.coroutines.CompletableDeferred::class.java).apply { isAccessible = true }
                    instrumentation.runOnMainSync {
                        handler.invoke(manager, """{"type":"transcript.partial","revision":1,"text":"A long synthetic transcript that should never wrap into multiple lines while recording. Check descenders in typography: gypsy puppy jumping quickly."}""", deferred)
                        handler.invoke(manager, """{"type":"assistant.message","revision":1,"text":"Which meeting should I mention?"}""", deferred)
                    }
                    SystemClock.sleep(500)
                    assertEquals("Recording must keep the voice layout visible",
                        app.whisperme.keyboard.ime.ImeUiMode.VOICE_RECORDING, manager.activeState.imeUiMode)
                    java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "android-startup-caption-question.png")).use {
                        instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    instrumentation.runOnMainSync {
                        handler.invoke(manager, """{"type":"assistant.message","revision":1,"text":""}""", deferred)
                        handler.invoke(manager, """{"type":"processing.status","stage":"gathering_context","text":"Gathering context…"}""", deferred)
                        @Suppress("UNCHECKED_CAST")
                        val processing = KeyboardManager::class.java.getDeclaredField("_isVoiceProcessing").apply { isAccessible = true }
                            .get(manager) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
                        processing.value = true
                        @Suppress("UNCHECKED_CAST")
                        val recording = KeyboardManager::class.java.getDeclaredField("_isVoiceRecording").apply { isAccessible = true }
                            .get(manager) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
                        recording.value = false
                    }
                    SystemClock.sleep(500)
                    assertEquals("Processing must keep the voice layout visible",
                        app.whisperme.keyboard.ime.ImeUiMode.VOICE_RECORDING, manager.activeState.imeUiMode)
                    java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "android-startup-caption-status.png")).use {
                        instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                if (it == 3) {
                    instrumentation.runOnMainSync {
                        KeyboardManager::class.java.getDeclaredMethod("stopVoiceRecording").apply { isAccessible = true }.invoke(manager)
                    }
                    val stoppedDeadline = SystemClock.elapsedRealtime() + 5000
                    while ((manager.isVoiceRecording.value || manager.isVoiceProcessing.value) &&
                        SystemClock.elapsedRealtime() < stoppedDeadline) SystemClock.sleep(20)
                    assertFalse("Immediate Stop must finish", manager.isVoiceRecording.value || manager.isVoiceProcessing.value)
                    assertNull("Immediate Stop must not produce a lifecycle error", manager.voiceErrorMessage.value)
                } else SystemClock.sleep(1500)
                instrumentation.runOnMainSync { manager.cancelVoiceProcessing() }
                SystemClock.sleep(500)
                assertFalse(manager.isVoiceRecording.value)
                assertTrue("Cancellation must stop active audio capture", audio.activeRecordingConfigurations.isEmpty())
                val retained = KeyboardManager::class.java.getDeclaredField("retainedVoiceAudio")
                    .apply { isAccessible = true }.get(manager) as RetainedVoiceAudio
                assertEquals("Cancellation must clear retained PCM", 0L, retained.durationMillis())
                assertFixtureText("Synthetic startup fixture. ")
            }
        } finally {
            instrumentation.runOnMainSync { manager.cancelVoiceProcessing() }
            fixture("finish")
        }
    }
}
