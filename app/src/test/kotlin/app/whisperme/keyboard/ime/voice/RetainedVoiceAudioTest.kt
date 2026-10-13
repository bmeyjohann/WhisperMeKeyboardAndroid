package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import app.whisperme.keyboard.app.settings.voiceinput.*

class RetainedVoiceAudioTest {
    @Test fun replayPreservesOrderAndDoesNotExposeMutableStorage() {
        val audio = RetainedVoiceAudio(4)
        val frame = byteArrayOf(1, 2)
        audio.append(frame)
        frame[0] = 9
        audio.append(byteArrayOf(3, 4))
        val replay = audio.snapshot()
        assertArrayEquals(byteArrayOf(1, 2), replay[0])
        assertArrayEquals(byteArrayOf(3, 4), replay[1])
        replay[0][0] = 8
        assertEquals(1.toByte(), audio.snapshot()[0][0])
        audio.clear()
        assertFalse(audio.isNotEmpty())
    }

    @Test fun overflowDoesNotDiscardEarlierAudio() {
        val audio = RetainedVoiceAudio(2)
        audio.append(byteArrayOf(1, 2))
        assertThrows(IllegalStateException::class.java) { audio.append(byteArrayOf(3)) }
        assertArrayEquals(byteArrayOf(1, 2), audio.snapshot().single())
    }

    @Test fun providerSwitchCannotKeepAnIncompatibleModel() {
        val groq = VoiceLlmProvider.GROQ_KIMI_K2
        val cerebras = VoiceLlmProvider.CEREBRAS_GLM_4_7
        assertEquals("qwen/qwen3.8-27b", groq.compatibleModel("gemma-4-31b"))
        assertEquals("gemma-4-31b", cerebras.compatibleModel("qwen/qwen3.8-27b"))
        for (provider in VoiceLlmProvider.values()) {
            for ((model, _) in provider.models()) assertEquals(model, provider.compatibleModel(model))
        }
    }
}
