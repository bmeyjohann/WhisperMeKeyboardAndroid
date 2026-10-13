package app.whisperme.keyboard.ime.voice

import android.view.View
import android.view.inputmethod.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.keyboardManager
import app.whisperme.keyboard.editorInstance
import app.whisperme.keyboard.ime.editor.FlorisEditorInfo
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InsertionOnlyContextTest {
    @Test fun unreadableEditableFieldUsesExplicitInsertionContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager by context.keyboardManager()
        val editor by context.editorInstance()
        instrumentation.runOnMainSync {
            val connection = object : BaseInputConnection(View(context), true) {
                override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
                override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? = null
            }
            val info = FlorisEditorInfo.wrap(EditorInfo().apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                packageName = "example.unreadable"
                fieldId = 7
            })
            val snapshot = KeyboardManager::class.java.declaredMethods.first { it.name == "buildVoiceContext" }
                .apply { isAccessible = true }.invoke(manager, info, editor.activeContent, connection)
            val json = JSONObject(KeyboardManager::class.java.declaredMethods.first { it.name == "buildVoiceContextJson" }
                .apply { isAccessible = true }.invoke(manager, snapshot, 2000, 2000) as String)
            assertTrue(json.getBoolean("insertOnly"))
            listOf("fullText", "selectedText", "textBeforeCursor", "textAfterCursor").forEach { assertEquals("", json.getString(it)) }
            assertEquals(0, json.getInt("cursorPosition"))
            assertFalse(json.has("contextWindow"))
            assertFalse(json.has("relatedFields"))
            assertEquals("example.unreadable", json.getString("packageName"))
        }
    }
}
