package app.whisperme.keyboard.ime.voice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceRecordingStopActionTest {
    @Test fun duplicateAndReentrantStopBroadcastsFinishOnlyOnce() {
        var finishes = 0
        lateinit var command: VoiceRecordingStopAction
        command = VoiceRecordingStopAction("current") {
            finishes++
            assertFalse(command.receive("current"))
        }
        assertTrue(command.receive("current"))
        assertTrue(command.stopRequested)
        assertFalse(command.receive("current"))
        assertEquals(1, finishes)
    }

    @Test fun oldAndMalformedCommandsCannotStopANewSession() {
        var finishes = 0
        val fresh = VoiceRecordingStopAction("new-session") { finishes++ }
        assertFalse(fresh.receive("old-session"))
        assertFalse(fresh.receive(null))
        assertFalse(fresh.stopRequested)
        assertEquals(0, finishes)
        assertTrue(fresh.receive("new-session"))
        assertEquals(1, finishes)
    }

    @Test fun disposedReceiverCannotInvokeCapturedRecorderCallback() {
        var finishes = 0
        val command = VoiceRecordingStopAction("session") { finishes++ }
        command.close()
        command.close()
        assertFalse(command.receive("session"))
        assertEquals(0, finishes)
    }

    @Test fun callbackFailureCannotReplayOrStartAnotherRecording() {
        var attempts = 0
        val command = VoiceRecordingStopAction("session") { attempts++; error("finish failed") }
        assertThrows(IllegalStateException::class.java) { command.receive("session") }
        assertTrue(command.stopRequested)
        assertFalse(command.receive("session"))
        assertEquals(1, attempts)
    }
}
