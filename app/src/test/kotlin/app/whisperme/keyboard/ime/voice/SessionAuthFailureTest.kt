package app.whisperme.keyboard.ime.voice

import app.whisperme.keyboard.app.auth.sessionHasNoRenewableCredentials
import app.whisperme.keyboard.app.auth.sessionNeedsSignIn
import com.auth0.android.authentication.AuthenticationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionAuthFailureTest {
    @Test fun onlyExplicitMissingCredentialsRequireSignIn() {
        assertTrue(sessionHasNoRenewableCredentials(IllegalStateException("No Credentials were previously set.")))
        assertTrue(sessionHasNoRenewableCredentials(IllegalStateException("Credentials need to be renewed but no Refresh Token is available to renew them.")))
        assertFalse(sessionHasNoRenewableCredentials(IllegalStateException("An error occurred while trying to use the Refresh Token to renew the Credentials.", java.io.IOException("offline"))))
    }
    @Test fun nestedRejectedRefreshRequiresLogin() {
        assertTrue(sessionNeedsSignIn(IllegalStateException("Could not renew credentials",
            AuthenticationException("invalid_grant", "Refresh token expired"))))
    }
    @Test fun networkAndServerFailuresRemainRetryable() {
        assertFalse(sessionNeedsSignIn(IllegalStateException("refresh_token request failed", java.io.IOException("offline"))))
        assertFalse(sessionNeedsSignIn(AuthenticationException("server_error", "Try again later")))
    }
}
