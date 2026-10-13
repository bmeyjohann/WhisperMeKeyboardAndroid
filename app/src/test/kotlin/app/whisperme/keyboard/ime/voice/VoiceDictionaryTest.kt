package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class VoiceDictionaryTest {
    @Test fun preservesExactSpellingAndDeduplicatesCorrections() {
        val first = addVoiceDictionaryWord("[]", "Meyjohann")
        val second = addVoiceDictionaryWord(first, "MEYJOHANN")
        assertEquals(listOf("MEYJOHANN"), readVoiceDictionary(second))
        assertEquals(second, addVoiceDictionaryWord(second, "MEYJOHANN"))
    }
    @Test fun boundsVocabularyWithoutSilentlyDroppingNewWords() {
        val full = Json.encodeToString((1..100).map { "word$it" })
        assertThrows(IllegalArgumentException::class.java) { addVoiceDictionaryWord(full, "new") }
        assertEquals(100, readVoiceDictionary(addVoiceDictionaryWord(full, "WORD1")).size)
        assertThrows(IllegalArgumentException::class.java) { addVoiceDictionaryWord("[]", "bad\nword") }
        assertThrows(IllegalArgumentException::class.java) { addVoiceDictionaryWord("[]", "x".repeat(81)) }
    }
}
