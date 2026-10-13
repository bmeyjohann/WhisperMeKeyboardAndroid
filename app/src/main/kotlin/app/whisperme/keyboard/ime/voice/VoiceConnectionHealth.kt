package app.whisperme.keyboard.ime.voice

/** Application-level RTT, deliberately independent of the cause of a delayed connection. */
internal class VoiceConnectionHealth {
    private var nextNonce = 0L
    private val pending = linkedMapOf<Long, Long>()
    var rttMs: Long? = null
        private set
    var missed = 0
        private set
    @Synchronized fun ping(now: Long): Long {
        val expired = pending.filterValues { now - it >= 10_000 }.keys
        missed += expired.size
        expired.forEach(pending::remove)
        return (++nextNonce).also { pending[it] = now }
    }
    @Synchronized fun pong(nonce: Long, now: Long): Boolean {
        val sent = pending.remove(nonce) ?: return false
        rttMs = (now - sent).coerceAtLeast(0)
        missed = 0
        return true
    }
    @Synchronized fun description(offline: Boolean): String = when {
        offline -> "Internet unavailable: device is offline"
        missed >= 2 -> "Connection to WhisperMe interrupted; cause unknown"
        missed > 0 || (rttMs ?: 0) >= 1500 -> "Connection to WhisperMe slow; cause unknown"
        rttMs != null -> "WhisperMe connection: ${rttMs} ms"
        else -> "Checking connection to WhisperMe…"
    }
}
