package app.whisperme.keyboard.ime.voice

/** Content-free completion timestamps; a milestone is recorded only once per attempt. */
internal class VoiceStartupTrace(private val clock: () -> Long, private val emit: (String, Long) -> Unit) {
    private val started = clock()
    private val completed = mutableSetOf<String>()
    @Synchronized fun mark(stage: String) {
        if (completed.add(stage)) emit(stage, (clock() - started).coerceAtLeast(0))
    }
}
