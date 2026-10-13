package app.whisperme.keyboard.ime.voice

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.app.florisPreferenceModel
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import app.whisperme.keyboard.keyboardManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses synthetic data on the owned emulator only. */
@RunWith(AndroidJUnit4::class)
class PreferenceUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 20000
        do {
            instrumentation.uiAutomation.windows.forEach { find(it.root, text)?.let { return it } }
            find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        screenshot("preferences-ui-failure.png")
        error("Visible text missing: $text")
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }

    @Test fun savedInstructionsLoadAutomaticallyInOneScreen() {
        val activity = instrumentation.startActivitySync(Intent(context, PreferenceUiFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            awaitText("Saved instructions (2)")
            awaitText("Concise writing")
            screenshot("preferences-overview.png")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun correctionOfferInKeyboardSavesOnlyAfterConfirmation() {
        val prefs by florisPreferenceModel()
        val dictionaryBefore = prefs.voiceInput.dictionary.get()
        val enabledBefore = prefs.voiceInput.useDictionary.get()
        val manager by context.keyboardManager()
        @Suppress("UNCHECKED_CAST")
        val pending = KeyboardManager::class.java.getDeclaredField("_preferenceSuggestion").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<VoicePreferenceSuggestion?>
        val service = instrumentation.uiAutomation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = service
        try {
            context.contentResolver.call(android.net.Uri.parse("content://${context.packageName}.android-startup"), "launch", null, null)
            val connectedDeadline = SystemClock.elapsedRealtime() + 10000
            while (app.whisperme.keyboard.FlorisImeService.currentInputConnection() == null && SystemClock.elapsedRealtime() < connectedDeadline) SystemClock.sleep(100)
            instrumentation.runOnMainSync { app.whisperme.keyboard.FlorisImeService.showUi() }
            context.contentResolver.call(android.net.Uri.parse("content://${context.packageName}.android-startup"), "seed", null,
                android.os.Bundle().apply { putString("text", "Hello MEYJOHANN."); putInt("start", 15); putInt("end", 15) })
            SystemClock.sleep(500)
            instrumentation.runOnMainSync {
                manager.cancelVoiceProcessing()
                pending.value = VoicePreferenceSuggestion("dictionary", "MEYJOHANN", "synthetic.editor")
            }
            awaitText("Remember spelling: MEYJOHANN")
            assertEquals(dictionaryBefore, prefs.voiceInput.dictionary.get())
            var chip: AccessibilityNodeInfo? = awaitText("Remember spelling: MEYJOHANN")
            while (chip != null && !chip.isClickable) chip = chip.parent
            assertTrue(chip?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            awaitText("Remember this spelling?")
            assertEquals(dictionaryBefore, prefs.voiceInput.dictionary.get())
            screenshot("dictionary-confirmation.png")
            var save: AccessibilityNodeInfo? = awaitText("Save")
            while (save != null && !save.isClickable) save = save.parent
            assertTrue(save?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (pending.value != null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertNull(pending.value)
            assertTrue(readVoiceDictionary(prefs.voiceInput.dictionary.get()).contains("MEYJOHANN"))
            assertEquals(enabledBefore, prefs.voiceInput.useDictionary.get())
        } finally {
            instrumentation.runOnMainSync {
                pending.value = null
                prefs.voiceInput.dictionary.set(dictionaryBefore)
                prefs.voiceInput.useDictionary.set(enabledBefore)
            }
            context.contentResolver.call(android.net.Uri.parse("content://${context.packageName}.android-startup"), "finish", null, null)
        }
    }
}
