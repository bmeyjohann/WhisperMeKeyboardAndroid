package app.whisperme.keyboard.ime.voice

/** Session-specific, one-shot command. No recording, editor access or persisted restart state. */
internal class VoiceRecordingStopAction(val action: String, onStop: () -> Unit) {
    private var callback: (() -> Unit)? = onStop
    var stopRequested: Boolean = false
        private set

    fun receive(receivedAction: String?): Boolean {
        if (receivedAction != action) return false
        val stop = callback ?: return false
        callback = null
        stopRequested = true
        stop()
        return true
    }

    fun close() { callback = null }
}
