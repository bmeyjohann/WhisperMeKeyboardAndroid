package app.whisperme.keyboard.ime.voice

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceRecordingNoticeTest {
    @Test fun insertionOnlyWarningReturnsToBackgroundStartupWithoutLooperFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var continuedStartup = false
        withContext(Dispatchers.IO) {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            // Exercise the real Android Toast, not a mocked notification callback.
            showInsertionOnlyNotice(context)
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            continuedStartup = true
        }
        assertTrue(continuedStartup)
    }
}
