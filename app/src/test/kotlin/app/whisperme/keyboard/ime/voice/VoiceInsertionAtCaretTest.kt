package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceInsertionAtCaretTest {
    @Test fun preservesBothSidesIncludingRepeatedCharacters() {
        assertEquals("a", voiceInsertionAtCaret("aaaa", "aaaaa", 2, 0))
        assertEquals("hello", voiceInsertionAtCaret("left right", "left helloright", 5, 0))
        assertEquals("hello", voiceInsertionAtCaret("", "hello", 0, 0))
    }
    @Test fun rejectsEditsDeletionAndSelectionReplacement() {
        assertNull(voiceInsertionAtCaret("Friday", "Thursday", 6, 0))
        assertNull(voiceInsertionAtCaret("abc", "ac", 1, 0))
        assertNull(voiceInsertionAtCaret("abc", "aXbc", 1, 1))
        assertNull(voiceInsertionAtCaret("abc", "Xabc", 2, 0))
        assertNull(voiceInsertionAtCaret("😀", "😀x", 1, 0))
    }
}
