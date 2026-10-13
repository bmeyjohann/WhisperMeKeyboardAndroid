package app.whisperme.keyboard.ime.voice

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

/** Abort, unlike Stop, must wake a replay producer blocked on a full channel. */
internal suspend fun abortVoiceProducer(queue: Channel<ByteArray>?, producer: Job?) {
    queue?.cancel()
    producer?.cancel()
    producer?.join()
}
