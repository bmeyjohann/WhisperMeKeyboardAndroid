package app.whisperme.keyboard.ime.voice

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PreparedVoiceResourceTest {
    @Test fun preparedAllocationIsTakenOnceAndClearDisposesOnlyUnclaimedResource() = runBlocking {
        val created = AtomicInteger()
        val disposed = mutableListOf<Int>()
        val resource = PreparedVoiceResource({ created.incrementAndGet() }, { disposed += it })
        resource.enable()
        resource.prepare(this)
        assertEquals(1, resource.take())
        resource.clear()
        assertTrue(disposed.isEmpty())
        resource.enable()
        resource.prepare(this)
        coroutineContext[Job]!!.children.toList().joinAll()
        resource.clear()
        assertEquals(listOf(2), disposed)
    }

    @Test fun hiddenKeyboardDisposesLateAllocationInsteadOfRetainingIt() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val resource = PreparedVoiceResource({ started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); 7 }, { disposed.countDown() })
        resource.enable()
        resource.prepare(this)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        resource.clear()
        finish.countDown()
        assertTrue(disposed.await(5, TimeUnit.SECONDS))
    }

    @Test fun disabledPreparationAllocatesNothing() = runBlocking {
        val resource = PreparedVoiceResource<Int>({ error("Hidden keyboard must not allocate") }, {})
        resource.prepare(this)
        resource.enable()
        resource.clear()
        resource.prepare(this)
    }
}
