package app.whisperme.keyboard.ime.voice

/** Press ownership survives asynchronous auth/microphone setup; one release produces one action. */
internal class VoiceButtonGesture {
    enum class Release { NONE, LATCH, FINISH, CANCEL }
    var held = false
        private set
    private var started = false
    private var busy = false
    var finishAfterStart = false
        private set
    fun down(recording: Boolean, busy: Boolean): Boolean {
        this.busy = busy
        held = true
        started = !busy && !recording
        return started
    }
    fun up(durationMs: Long, canceled: Boolean): Release {
        if (!held) return Release.NONE
        held = false
        if (canceled) {
            finishAfterStart = false
            return if (started) Release.CANCEL else Release.NONE
        }
        if (busy) return Release.NONE
        if (!started || durationMs >= 1000) {
            finishAfterStart = true
            return Release.FINISH
        }
        return Release.LATCH
    }
    fun consumeFinish(): Boolean = finishAfterStart.also { finishAfterStart = false }
    fun reset() { held = false; started = false; busy = false; finishAfterStart = false }
}
