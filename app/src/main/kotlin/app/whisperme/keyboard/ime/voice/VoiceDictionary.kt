package app.whisperme.keyboard.ime.voice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun readVoiceDictionary(raw: String): List<String> = runCatching {
    Json.decodeFromString<List<String>>(raw).filter { validDictionaryWord(it) }.distinctBy { it.lowercase() }
}.getOrDefault(emptyList())

internal fun validDictionaryWord(word: String): Boolean = word.isNotBlank() && word.length <= 80 &&
    word.none { it.code < 32 }

internal fun addVoiceDictionaryWord(raw: String, word: String): String {
    val trimmed = word.trim()
    require(validDictionaryWord(trimmed)) { "Use a word or short name of up to 80 characters." }
    val words = readVoiceDictionary(raw).filterNot { it.equals(trimmed, ignoreCase = true) } + trimmed
    requireVoiceDictionaryBudget(words)
    return Json.encodeToString(words.sortedBy { it.lowercase() })
}

internal fun requireVoiceDictionaryBudget(words: List<String>) {
    require(words.size <= 100) { "The dictionary holds up to 100 entries. Remove one in Saved preferences first." }
    require(words.all(::validDictionaryWord)) { "Use words or short names of up to 80 characters." }
    require(words.sumOf(::utf8Bytes) <= VoicePreferenceLimits.DICTIONARY_BYTES) {
        "The dictionary exceeds 4,096 UTF-8 bytes (about 1,024 tokens). Shorten or remove entries in Saved preferences."
    }
}
