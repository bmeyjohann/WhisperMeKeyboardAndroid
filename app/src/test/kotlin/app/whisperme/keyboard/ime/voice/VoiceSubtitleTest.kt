package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceSubtitleTest {
    @Test fun keepsShortTextForSingleLineViewport() {
        assertEquals("Old sentence. Second sentence. Still speaking", voiceSubtitle("Old sentence. Second sentence. Still speaking"))
    }
    @Test fun longUnpunctuatedSpeechKeepsNewestWords() {
        val full = "old words ".repeat(1000) + "latest words"
        val subtitle = voiceSubtitle(full)
        assertTrue(subtitle.length <= 151)
        assertTrue(subtitle.endsWith("latest words"))
        assertTrue(full.length > 1000)
    }
    @Test fun whitespaceAndEmptyInput() {
        assertEquals("", voiceSubtitle(" \n "))
        assertEquals("Hello there", voiceSubtitle(" Hello\nthere "))
    }
    @Test fun preservesUnicodeSuffixAndDescenders() {
        val shown = voiceSubtitle("😀".repeat(200) + " g j p q y")
        assertTrue(shown.endsWith("g j p q y"))
        assertFalse(shown.contains('\uFFFD'))
        assertFalse(shown.drop(1).first().isLowSurrogate())
    }
}
