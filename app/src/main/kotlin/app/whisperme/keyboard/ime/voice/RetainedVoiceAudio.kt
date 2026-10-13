package app.whisperme.keyboard.ime.voice

/** Memory-only PCM retained until success or explicit discard; never silently truncates. */
class RetainedVoiceAudio(private val maxBytes: Int = 16_000 * 2 * 600) {
    private val frames = mutableListOf<ByteArray>()
    private var size = 0

    @Synchronized fun append(frame: ByteArray) {
        check(size + frame.size <= maxBytes) { "Recording limit reached; retry the retained audio or discard it" }
        frames.add(frame.copyOf())
        size += frame.size
    }

    @Synchronized fun snapshot(): List<ByteArray> = frames.map { it.copyOf() }
    @Synchronized fun isNotEmpty(): Boolean = size > 0
    @Synchronized fun durationMillis(): Long = size.toLong() / 32
    @Synchronized fun clear() { frames.forEach { it.fill(0) }; frames.clear(); size = 0 }
}
