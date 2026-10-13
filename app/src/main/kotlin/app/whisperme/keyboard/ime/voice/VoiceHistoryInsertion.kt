package app.whisperme.keyboard.ime.voice

/** Returns a diagnostic without including editor contents. Never falls back to destructive deletion. */
internal fun applyVoiceHistoryResult(
    unchanged: Boolean,
    selectAll: () -> Boolean,
    commit: () -> Boolean,
    verify: () -> Boolean,
): String? = try {
    when {
        unchanged -> null
        !selectAll() -> "insertion.failed: the app rejected or did not confirm the requested text selection (setSelection); replacement text was not inserted"
        !commit() -> "insertion.failed: InputConnection.commitText returned false; check the field before copying the saved result"
        !verify() -> "insertion.failed: editor text did not confirm the result; check the field before copying the saved result"
        else -> null
    }
} catch (e: Exception) {
    "insertion.failed: ${e.javaClass.simpleName}: ${e.message ?: "No exception message"}"
}
