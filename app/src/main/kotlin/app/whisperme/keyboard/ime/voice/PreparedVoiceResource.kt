package app.whisperme.keyboard.ime.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns an allocated, inactive resource. Cancellation never strands a late native allocation. */
internal class PreparedVoiceResource<T>(private val create: () -> T, private val dispose: (T) -> Unit) {
    private val lock = Any()
    private var ready: T? = null
    private var preparation: Job? = null
    private var generation = 0L
    private var enabled = false

    fun enable() = synchronized(lock) { enabled = true }

    fun prepare(scope: CoroutineScope) = synchronized(lock) {
        if (!enabled || ready != null || preparation?.isActive == true) return@synchronized
        val expected = generation
        preparation = scope.launch(Dispatchers.IO) {
            var resource: T? = null
            try {
                resource = create()
                synchronized(lock) {
                    if (enabled && expected == generation && isActive) {
                        ready = resource
                        resource = null
                    }
                }
            } catch (_: Exception) {
                // Preparation is optional. A recording retries allocation and reports its actual error.
            } finally {
                resource?.let(dispose)
            }
        }
    }

    suspend fun take(): T {
        synchronized(lock) { preparation }?.join()
        currentCoroutineContext().ensureActive()
        val resource = synchronized(lock) { ready.also { ready = null } } ?: create()
        try {
            currentCoroutineContext().ensureActive()
            return resource
        } catch (e: Exception) { dispose(resource); throw e }
    }

    fun clear() {
        val resource = synchronized(lock) {
            generation++
            enabled = false
            preparation?.cancel()
            preparation = null
            ready.also { ready = null }
        }
        resource?.let(dispose)
    }
}
