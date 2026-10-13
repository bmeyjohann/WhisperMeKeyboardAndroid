package app.whisperme.keyboard.app.auth

import com.auth0.android.authentication.AuthenticationException

/** CredentialsManager wraps the OAuth error; generic refresh wording also occurs on network failures. */
internal fun sessionNeedsSignIn(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(10).filterIsInstance<AuthenticationException>()
        .any { it.getCode() in setOf("invalid_grant", "login_required", "unauthorized") }

/** Exact local failures from the pinned Auth0 2.11 SDK, not generic refresh/network wording. */
internal fun sessionHasNoRenewableCredentials(error: Throwable): Boolean = error.cause == null &&
    error.message in setOf("No Credentials were previously set.",
        "Credentials need to be renewed but no Refresh Token is available to renew them.")
