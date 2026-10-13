package app.whisperme.keyboard.ime.voice

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.ime.keyboard.KeyboardManager
import app.whisperme.keyboard.keyboardManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceResponseNoticeTest {
    @Test fun ruleSavedNoticeCompletesResponseOnSocketThread() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager by instrumentation.targetContext.keyboardManager()
        val response = CompletableDeferred<Any>()
        val handler = KeyboardManager::class.java.declaredMethods.first {
            it.name == "handleRealtimeWebSocketMessage"
        }.apply { isAccessible = true }

        withContext(Dispatchers.IO) {
            assertNull(Looper.myLooper())
            handler.invoke(manager,
                """{"type":"processing.final_text","finalText":"","notice":"Preference saved for this app."}""",
                response)
        }

        assertTrue("A notice must not interrupt final response delivery", response.isCompleted)
        val result = response.await()
        fun field(name: String): Any? = result.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(result)
        assertEquals(true, field("success"))
        assertEquals("", field("finalText"))
        assertEquals("Preference saved for this app.", field("notice"))
        // Exercise the real notification too, through normal UI-thread delivery.
        instrumentation.runOnMainSync {
            KeyboardManager::class.java.getDeclaredMethod("processVoiceResponse", result.javaClass)
                .apply { isAccessible = true }.invoke(manager, result)
            manager.cancelVoiceProcessing()
        }
    }
}
