package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceTextDeltaTest {
    @Test fun changesOnlyMiddleAndLeavesLongEdgesUntouched() {
        val original = "prefix ".repeat(20_000) + "old" + " suffix".repeat(20_000)
        val updated = original.replace("old", "new text")
        assertEquals(VoiceTextDelta(140_000, 140_003, "new text"), voiceTextDelta(original, updated))
    }
    @Test fun handlesInsertionDeletionAndUnchangedText() {
        assertNull(voiceTextDelta("same", "same"))
        assertEquals(VoiceTextDelta(5, 5, " next"), voiceTextDelta("hello", "hello next"))
        assertEquals(VoiceTextDelta(1, 2, ""), voiceTextDelta("abc", "ac"))
    }
    @Test fun neitherEndSplitsSurrogatesWhenEmojiShareOneCodeUnit() {
        for ((original, updated) in listOf("a😀b" to "a😁b", "😀end" to "x😀end", "😀" to "😁", "a😀b" to "aXb")) {
            val change = checkNotNull(voiceTextDelta(original, updated))
            assertFalse(splitsSurrogate(original, change.start))
            assertFalse(splitsSurrogate(original, change.oldEnd))
            assertEquals(updated, original.substring(0, change.start) + change.replacement + original.substring(change.oldEnd))
        }
    }
}
