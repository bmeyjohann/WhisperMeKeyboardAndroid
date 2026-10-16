package app.whisperme.keyboard.ime.voice

import android.os.SystemClock
import android.os.Bundle
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.FlorisImeService
import app.whisperme.keyboard.keyboardManager
import app.whisperme.keyboard.app.auth.LocalVoiceSession
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceContinuityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager by lazy { val value by context.keyboardManager(); value }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue("$message: ${manager.voiceErrorMessage.value}", condition())
    }
    private fun fixture(method: String, extras: Bundle? = null) = checkNotNull(context.contentResolver.call(
        android.net.Uri.parse("content://${context.packageName}.android-startup"), method, null, extras))
    private fun server(path: String): org.json.JSONObject {
        val connection = java.net.URL("http://127.0.0.1:8877/_fixture/$path").openConnection() as java.net.HttpURLConnection
        return try {
            connection.setRequestProperty("Authorization", "Bearer ${LocalVoiceSession.token(context)}")
            org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun call(name: String) = main {
        KeyboardManager::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(manager)
    }
    private fun prepare() {
        main { manager.cancelVoiceProcessing() }
        server("reset")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "ime set ${context.packageName}/app.whisperme.keyboard.FlorisImeService")).use { it.readBytes() }
        fixture("launch")
        waitFor("Owned editor ready") { runCatching { fixture("snapshot").containsKey("length") }.getOrDefault(false) }
        SystemClock.sleep(500)
        waitFor("IME connected") { FlorisImeService.currentInputConnection() != null }
        fixture("seed", Bundle().apply { putString("text", ""); putInt("start", 0); putInt("end", 0) })
        SystemClock.sleep(400)
        main { FlorisImeService.showUi() }
        waitFor("Owned input view visible") { currentIme()?.isInputViewShown == true &&
            currentIme()?.currentInputEditorInfo?.hintText?.toString() == "Message body" }
        call("startVoiceRecording")
        waitFor("Capture started") { manager.isVoiceRecording.value }
        waitFor("Caption received") { manager.voiceLiveTranscript.value.isNotBlank() }
    }
    private fun currentIme(): FlorisImeService? {
        val service = Class.forName("app.whisperme.keyboard.FlorisImeServiceKt")
            .getDeclaredField("FlorisImeServiceReference").apply { isAccessible = true }
            .get(null) as java.lang.ref.WeakReference<*>
        return service.get() as? FlorisImeService
    }
    private fun assertAwake(awake: Boolean) = main {
        val ime = checkNotNull(currentIme())
        assertEquals(awake, ime.window.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }
    @Test fun hiddenKeyboardKeepsSameSessionAndRetryUsesCompletedText() {
        try {
            prepare()
            assertAwake(true)
            main { FlorisImeService.hideUi() }
            SystemClock.sleep(700)
            assertTrue("Hiding must retain microphone capture", manager.isVoiceRecording.value)
            assertEquals(1, server("stats").getInt("starts"))
            assertAwake(false)
            main { FlorisImeService.showUi() }
            SystemClock.sleep(500)
            assertTrue(manager.isVoiceRecording.value)
            assertAwake(true)
            call("stopVoiceRecording")
            waitFor("Completed ASR retained after LLM failure") { manager.isVoiceRetryable.value }
            assertEquals("Saved first sentence.", manager.voiceLiveTranscript.value)
            main { manager.retryVoiceProcessing() }
            waitFor("Text-only retry") { server("stats").getInt("textOnly") == 1 && !manager.isVoiceProcessing.value }
            assertEquals(1, server("stats").getJSONArray("bytes").length())
            assertEquals("Saved first sentence.", manager.voiceFinalText.value)
            assertAwake(false)
        } finally { main { manager.cancelVoiceProcessing() }; fixture("finish") }
    }
    @Test fun keepDictatingSendsCompletedPrefixAndOnlyNewAudio() {
        try {
            prepare(); call("stopVoiceRecording")
            waitFor("Recovery ready") { manager.isVoiceRetryable.value }
            assertTrue(manager.canContinueVoiceRecording())
            main { manager.continueVoiceRecording() }
            waitFor("New capture started") { manager.isVoiceRecording.value && server("stats").getInt("starts") == 2 }
            waitFor("New audio received") { server("stats").getJSONArray("bytes").getInt(1) > 0 }
            assertEquals("Saved first sentence.", server("stats").getJSONArray("prefixes").getString(1))
            call("stopVoiceRecording")
            waitFor("Combined processing finished") { !manager.isVoiceRecording.value && !manager.isVoiceProcessing.value }
            assertEquals("Saved first sentence. New speech.", server("stats").getString("lastTranscript"))
            assertEquals(0, server("stats").getInt("textOnly"))
            assertEquals("Saved first sentence. New speech.", manager.voiceFinalText.value)
        } finally { main { manager.cancelVoiceProcessing() }; fixture("finish") }
    }
    @Test fun hidingDuringProcessingPreservesResultWithoutHiddenInsertion() {
        try {
            prepare(); server("delay"); call("stopVoiceRecording")
            waitFor("Processing started") { manager.isVoiceProcessing.value }
            main { FlorisImeService.hideUi() }
            waitFor("Hidden result preserved") { !manager.isVoiceProcessing.value && manager.voiceFinalText.value != null }
            assertEquals("Saved first sentence.", manager.voiceFinalText.value)
            assertEquals(0, fixture("snapshot").getInt("length"))
            assertEquals(1, server("stats").getInt("starts"))
            assertAwake(false)
        } finally { main { manager.cancelVoiceProcessing() }; fixture("finish") }
    }
    @Test fun backgroundNotificationStopFinishesAndPreservesTranscript() {
        try {
            prepare()
            val notifications = context.getSystemService(android.app.NotificationManager::class.java)
            val notification = notifications.activeNotifications.first { it.notification.channelId == "whisperme_voice_recording" }
            main { FlorisImeService.hideUi() }
            SystemClock.sleep(300)
            assertTrue(manager.isVoiceRecording.value)
            notification.notification.actions.first().actionIntent.send()
            waitFor("Notification stop finalized ASR") { !manager.isVoiceRecording.value && manager.isVoiceRetryable.value }
            assertEquals("Saved first sentence.", manager.voiceLiveTranscript.value)
            assertEquals(0, fixture("snapshot").getInt("length"))
        } finally { main { manager.cancelVoiceProcessing() }; fixture("finish") }
    }
    @Test fun unavailableBackgroundProtectionFlushesInsteadOfCancelling() {
        try {
            prepare()
            main {
                KeyboardManager::class.java.getDeclaredField("voiceBackgroundProtected").apply { isAccessible = true }.setBoolean(manager, false)
                FlorisImeService.hideUi()
            }
            waitFor("Unprotected hide finalized ASR") { !manager.isVoiceRecording.value && manager.isVoiceRetryable.value }
            assertEquals("Saved first sentence.", manager.voiceLiveTranscript.value)
            assertEquals(1, server("stats").getInt("starts"))
        } finally { main { manager.cancelVoiceProcessing() }; fixture("finish") }
    }
    @Test fun recordingKeepsDisplayAwakePastTheScreenTimeout() {
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val timeout = shell("settings get system screen_off_timeout")
        val plugged = shell("settings get global stay_on_while_plugged_in")
        try {
            prepare()
            shell("settings put system screen_off_timeout 15000")
            shell("settings put global stay_on_while_plugged_in 0")
            SystemClock.sleep(18_000)
            assertTrue(manager.isVoiceRecording.value)
            assertTrue(context.getSystemService(android.os.PowerManager::class.java).isInteractive)
            assertAwake(true)
        } finally {
            shell("settings put system screen_off_timeout $timeout")
            shell("settings put global stay_on_while_plugged_in $plugged")
            main { manager.cancelVoiceProcessing() }; fixture("finish")
        }
    }
}
