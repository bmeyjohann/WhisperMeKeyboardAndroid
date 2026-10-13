package app.whisperme.keyboard.ime.voice

internal data class VoiceTextDelta(val start: Int, val oldEnd: Int, val replacement: String)

/** A typing-only operation must preserve both sides exactly and cannot replace a selection. */
internal fun voiceInsertionAtCaret(original: String, updated: String, caret: Int, selectionLength: Int): String? {
    if (selectionLength != 0 || caret !in 0..original.length || splitsSurrogate(original, caret)) return null
    val added = updated.length - original.length
    if (added < 0 || !updated.startsWith(original.substring(0, caret)) ||
        !updated.endsWith(original.substring(caret))) return null
    return updated.substring(caret, caret + added)
}

/** Replace only changed text so Android retains spans outside the edited range. */
internal fun voiceTextDelta(original: String, updated: String): VoiceTextDelta? {
    if (original == updated) return null
    var start = 0
    while (start < original.length && start < updated.length && original[start] == updated[start]) start++
    if (splitsSurrogate(original, start) || splitsSurrogate(updated, start)) start--
    var suffix = 0
    while (suffix < original.length - start && suffix < updated.length - start &&
        original[original.lastIndex - suffix] == updated[updated.lastIndex - suffix]) suffix++
    while (suffix > 0 && (splitsSurrogate(original, original.length - suffix) ||
        splitsSurrogate(updated, updated.length - suffix))) suffix--
    return VoiceTextDelta(start, original.length - suffix, updated.substring(start, updated.length - suffix))
}
