package app.whisperme.keyboard.ime.voice

/** One ordinary typing operation. Never select, rewrite, or automatically retry an unconfirmed commit. */
internal fun commitUnverifiableVoiceInsertion(
    finalText: String?,
    hasEditingWindow: Boolean,
    commit: (String) -> Boolean,
): String {
    if (finalText == null) return "No final insertion received; nothing was inserted. Copy the transcript to recover it."
    if (hasEditingWindow) return "An editing result was returned for an insertion-only field; nothing was inserted. Copy the result to recover it."
    if (finalText.isEmpty()) return "No insertion text was returned; nothing was inserted."
    return try {
        if (commit(finalText)) "Insertion sent, but this app cannot confirm the text or cursor. Check the field before copying or trying again."
        else "The app did not confirm insertion. Check the field; copy the result if it is missing."
    } catch (e: Exception) {
        "Insertion could not be confirmed (${e.javaClass.simpleName}). Check the field; copy the result if it is missing."
    }
}
