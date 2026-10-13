package app.whisperme.keyboard.ime.voice

/** UTF-16 offsets match Android InputConnection and the wire protocol. Full text stays local. */
internal data class VoiceContextWindow(val start: Int, val end: Int, val totalLength: Int) {
    val hasBefore get() = start > 0
    val hasAfter get() = end < totalLength
    fun contains(previous: VoiceContextWindow): Boolean = totalLength == previous.totalLength &&
        start <= previous.start && end >= previous.end

    fun apply(original: String, replacement: String, cursorPosition: Int?): Pair<String, Int> {
        require(original.length == totalLength) { "Original editor snapshot is unavailable" }
        val cursor = cursorPosition ?: replacement.length
        require(cursor in 0..replacement.length && !splitsSurrogate(replacement, cursor)) {
            "The editing result contains an invalid cursor position"
        }
        val result = original.substring(0, start) + replacement + original.substring(end)
        require(result.length <= 1_000_000) { "The editing result exceeds the safe field size" }
        return result to start + cursor
    }
}

internal fun splitsSurrogate(text: String, offset: Int): Boolean =
    offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()

internal fun voiceContextWindow(text: String, selectionStart: Int, selectionEnd: Int,
    beforeChars: Int = 2_000, afterChars: Int = 2_000): VoiceContextWindow {
    require(beforeChars in 0..12_000 && afterChars in 0..12_000) { "Requested context exceeds the safe window size" }
    require(text.length <= 1_000_000) { "This field exceeds the safe voice editing size" }
    require(selectionStart in 0..text.length && selectionEnd in selectionStart..text.length &&
        !splitsSurrogate(text, selectionStart) && !splitsSurrogate(text, selectionEnd)) {
        "This editor does not expose a valid selection for safe voice editing"
    }
    require(selectionEnd - selectionStart <= 8_000) { "Select at most 8,000 characters for voice editing" }
    var start = (selectionStart - beforeChars).coerceAtLeast(0)
    var end = (selectionEnd + afterChars).coerceAtMost(text.length)
    // Include the complete boundary code point: at most one extra UTF-16 unit per side.
    if (splitsSurrogate(text, start)) start--
    if (splitsSurrogate(text, end)) end++
    return VoiceContextWindow(start, end, text.length)
}

internal fun voiceFieldRole(fieldType: String?, hint: String?, label: String?): String {
    if (fieldType == "email") return "email_address"
    val name = listOfNotNull(label, hint).joinToString(" ").trim().lowercase()
    return when {
        Regex("\\b(subject|betreff)\\b").containsMatchIn(name) -> "email_subject"
        Regex("\\b(email body|message body|mail body|nachrichtentext|e-mail-text)\\b").containsMatchIn(name) -> "email_body"
        Regex("\\b(search|suchen|suche)\\b").containsMatchIn(name) -> "search"
        name.isNotEmpty() -> "text"
        else -> "unknown"
    }
}
