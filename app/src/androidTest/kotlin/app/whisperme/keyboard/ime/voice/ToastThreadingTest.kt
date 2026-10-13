package app.whisperme.keyboard.ime.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisperme.keyboard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.ToastHandle
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.android.showShortToast
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ToastThreadingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class CheckedContext(base: Context) : ContextWrapper(base) {
        val resourceReads = AtomicInteger()
        override fun getResources(): Resources {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Toast touched resources off Main" }
            resourceReads.incrementAndGet()
            return super.getResources()
        }
    }

    @Test fun allOverloadsCreateOnMainAndCancelFromBackground() = runBlocking {
        val context = CheckedContext(instrumentation.targetContext)
        val calls: List<() -> ToastHandle> = listOf(
            { context.showShortToast("short") },
            { context.showLongToast("long") },
            { context.showShortToast(R.string.floris_app_name) },
            { context.showLongToast(R.string.floris_app_name) },
            { context.showShortToast(R.string.about__version_copied__error, "error_message" to "test") },
            { context.showLongToast(R.string.about__version_copied__error, "error_message" to "test") },
        )
        for (dispatcher in listOf(Dispatchers.IO, Dispatchers.Default, Dispatchers.Main)) {
            for (call in calls) {
                val before = context.resourceReads.get()
                val handle = withContext(dispatcher) { call() }
                instrumentation.waitForIdleSync()
                assertTrue("Toast was actually constructed", context.resourceReads.get() > before)
                withContext(Dispatchers.IO) { handle.cancel() }
                instrumentation.waitForIdleSync()
            }
        }
    }

    @Test fun workerWithItsOwnLooperStillUsesMain() {
        val context = CheckedContext(instrumentation.targetContext)
        val worker = HandlerThread("toast-regression").apply { start() }
        val complete = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val handle = AtomicReference<ToastHandle?>()
        try {
            Handler(worker.looper).post {
                try { handle.set(context.showShortToast("worker looper")) }
                catch (error: Throwable) { failure.set(error) }
                finally { complete.countDown() }
            }
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            instrumentation.waitForIdleSync()
            assertTrue(context.resourceReads.get() > 0)
        } finally {
            handle.get()?.cancel()
            worker.quitSafely()
            worker.join(5000)
        }
    }

    @Test fun cancelBeforeMainDispatchPreventsTheToast() {
        val context = CheckedContext(instrumentation.targetContext)
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            // Keep Main occupied while a caller queues and immediately cancels the notice.
            val worker = Thread {
                try { context.showShortToast("must not appear").cancel() }
                catch (error: Throwable) { failure.set(error) }
            }.apply { start() }
            worker.join(5000)
            assertFalse("Notifications must not block callers waiting for Main", worker.isAlive)
        }
        failure.get()?.let { throw it }
        instrumentation.waitForIdleSync()
        assertEquals(0, context.resourceReads.get())
    }
}
