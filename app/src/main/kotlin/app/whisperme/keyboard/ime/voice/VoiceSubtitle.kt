package app.whisperme.keyboard.ime.voice

/** Presentation only: the complete transcript remains available for copying and processing. */
fun voiceSubtitle(transcript: String): String {
    val normalized = transcript.trim().replace(Regex("\\s+"), " ")
    val recent = normalized
    if (recent.length <= 150) return recent
    // Do not cut a surrogate pair when bounding the presentation buffer.
    val count = recent.codePointCount(0, recent.length)
    val tail = recent.substring(recent.offsetByCodePoints(0, (count - 150).coerceAtLeast(0)))
    val boundary = tail.indexOf(' ')
    return "…" + if (boundary in 0..40) tail.substring(boundary + 1) else tail
}
