package app.whisperme.keyboard.ime.voice

import app.whisperme.keyboard.app.auth.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BackendSessionTest {
    @Test fun onlyExplicitBooleanCapabilityEnablesInsertionOnly() {
        assertTrue(backendAllowsInsertOnly("""{"features":{"insertOnly":true}}"""))
        for (body in listOf("{}", "broken", """{"features":{"insertOnly":false}}""", """{"features":{"insertOnly":"true"}}""")) {
            assertFalse(backendAllowsInsertOnly(body))
        }
    }
    @Test fun expiredBackendTokenRefreshesOnceAndUsesReplacement() = runBlocking {
        val refreshes = mutableListOf<Boolean>()
        val checked = mutableListOf<String>()
        val result = validateBackendSession(
            { force -> refreshes += force; if (force) "new" else "old" },
            { token -> checked += token; if (token == "old") 401 else 200 })
        assertEquals(BackendSession.Accepted("new"), result)
        assertEquals(listOf(false, true), refreshes)
        assertEquals(listOf("old", "new"), checked)
    }
    @Test fun repeated401RequiresSignInWithoutLooping() = runBlocking {
        var attempts = 0
        assertEquals(BackendSession.Rejected, validateBackendSession({ "token" }, { attempts++; 401 }))
        assertEquals(2, attempts)
    }
    @Test fun backendOutageNeverRefreshesOrSignsOut() = runBlocking {
        for (status in listOf(403, 429, 500, 503)) {
            val refreshes = mutableListOf<Boolean>()
            assertEquals(BackendSession.Retry, validateBackendSession({ refreshes += it; "token" }, { status }))
            assertEquals(listOf(false), refreshes)
        }
    }
    @Test fun missingCredentialsNeverReachBackend() = runBlocking {
        assertEquals(BackendSession.NoToken, validateBackendSession({ null }, { error("Must not call") }))
    }
    @Test fun networkFailureIsNotConvertedToSignIn() = runBlocking {
        var refreshes = 0
        try {
            validateBackendSession({ refreshes++; "token" }, { throw java.io.IOException("offline") })
            fail<Unit>("Expected network error")
        } catch (expected: java.io.IOException) { assertEquals(1, refreshes) }
    }
}
