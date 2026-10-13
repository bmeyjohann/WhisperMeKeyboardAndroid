package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceButtonGestureTest {
    @Test fun tapLatchesAndNextTapStopsOnce() {
        val gesture = VoiceButtonGesture()
        assertTrue(gesture.down(recording = false, busy = false))
        assertEquals(VoiceButtonGesture.Release.LATCH, gesture.up(999, false))
        assertFalse(gesture.consumeFinish())
        assertFalse(gesture.down(recording = true, busy = false))
        assertEquals(VoiceButtonGesture.Release.FINISH, gesture.up(100, false))
        assertTrue(gesture.consumeFinish())
        assertFalse(gesture.consumeFinish())
        assertEquals(VoiceButtonGesture.Release.NONE, gesture.up(100, false))
    }
    @Test fun longReleaseBeforeAsyncStartupKeepsOnePendingFinish() {
        val gesture = VoiceButtonGesture()
        gesture.down(false, false)
        assertEquals(VoiceButtonGesture.Release.FINISH, gesture.up(1000, false))
        assertTrue(gesture.finishAfterStart)
        assertTrue(gesture.consumeFinish())
        assertFalse(gesture.consumeFinish())
    }
    @Test fun cancellationNeverStartsOrStopsAnUnownedRecording() {
        val gesture = VoiceButtonGesture()
        gesture.down(false, false)
        assertEquals(VoiceButtonGesture.Release.CANCEL, gesture.up(1001, true))
        assertFalse(gesture.finishAfterStart)
        gesture.reset()
        gesture.down(true, false)
        assertEquals(VoiceButtonGesture.Release.NONE, gesture.up(1001, true))
        gesture.down(false, true)
        assertEquals(VoiceButtonGesture.Release.NONE, gesture.up(1001, false))
        assertFalse(gesture.finishAfterStart)
    }
}
