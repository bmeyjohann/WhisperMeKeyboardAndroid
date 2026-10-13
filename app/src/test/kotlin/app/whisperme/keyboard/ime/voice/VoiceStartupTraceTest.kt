package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceStartupTraceTest {
    @Test fun recordsActualCompletionOnceEvenWhenStagesFinishOutOfOrder() {
        var time = 10L
        val events = mutableListOf<Pair<String, Long>>()
        val trace = VoiceStartupTrace({ time }) { stage, elapsed -> events += stage to elapsed }
        time = 40; trace.mark("microphone")
        time = 60; trace.mark("token")
        time = 100; trace.mark("microphone")
        assertEquals(listOf("microphone" to 30L, "token" to 50L), events)
    }
}
