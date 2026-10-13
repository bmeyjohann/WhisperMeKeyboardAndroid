package app.whisperme.keyboard.app.auth

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationAPIClient
import com.auth0.android.authentication.storage.CredentialsManager
import com.auth0.android.authentication.storage.CredentialsManagerException
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import com.auth0.android.callback.Callback
import com.auth0.android.provider.WebAuthProvider
import com.auth0.android.result.Credentials
import com.auth0.android.result.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.whisperme.keyboard.R

class Auth0Manager private constructor(context: Context) {
    enum class SessionStatus { CHECKING, READY, SIGN_IN_REQUIRED, RETRY }
    private val _sessionStatus = MutableStateFlow(SessionStatus.CHECKING)
    val sessionStatus: StateFlow<SessionStatus> = _sessionStatus.asStateFlow()
    private val sessionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var sessionCheck: kotlinx.coroutines.Job? = null

    @Synchronized fun checkSessionBeforeUse() {
        if (sessionCheck?.isActive == true) return
        sessionCheck = sessionScope.launch {
            try {
                kotlinx.coroutines.withTimeout(15_000) { getBackendAccessToken() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _sessionStatus.value = SessionStatus.RETRY
            }
        }
    }
    private val localContext = context.applicationContext
    private val localMode = LocalVoiceSession.configured(context)
    private val account: Auth0
    private val credentialsManager: CredentialsManager
    private val authenticationApiClient: AuthenticationAPIClient

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private val _accessToken = MutableStateFlow<String?>(null)
    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

    // Track token expiration time (in milliseconds since epoch)
    private val _tokenExpirationTime = MutableStateFlow<Long?>(null)

    // Track if refresh is currently in progress to prevent concurrent refreshes
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    private val backendSessionMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile var backendDefaultLlmProvider: String? = null
        private set
    private val _backendDefaultLlmModel = MutableStateFlow<String?>(null)
    val backendDefaultLlmModel = _backendDefaultLlmModel.asStateFlow()
    @Volatile var backendSupportsResumeTranscript: Boolean = false
        private set
    @Volatile var backendSupportsHeartbeat: Boolean = false
        private set
    @Volatile var backendSupportsProcessTranscript: Boolean = false
        private set
    @Volatile var backendSupportsInsertOnly: Boolean = false
        private set


    // Callback for when authentication state changes
    private var authStateChangeCallback: ((Boolean) -> Unit)? = null

    private fun updateStateFromCredentials(
        credentials: Credentials,
        fetchProfile: Boolean,
        notifyChange: Boolean,
    ) {
        val wasAuthenticated = _isAuthenticated.value
        _isAuthenticated.value = true
        _sessionStatus.value = SessionStatus.READY
        _accessToken.value = credentials.accessToken
        _tokenExpirationTime.value = credentials.expiresAt?.time

        if (fetchProfile) {
            credentials.accessToken?.let { accessToken ->
                getUserProfile(accessToken)
            }
        }

        if (notifyChange && !wasAuthenticated) {
            notifyAuthStateChange(true)
        }
    }

    private fun clearAuthenticationState(notifyChange: Boolean) {
        val wasAuthenticated = _isAuthenticated.value
        _isAuthenticated.value = false
        _sessionStatus.value = SessionStatus.SIGN_IN_REQUIRED
        _accessToken.value = null
        _userProfile.value = null
        _tokenExpirationTime.value = null

        if (notifyChange && wasAuthenticated) {
            notifyAuthStateChange(false)
        }
    }

    init {
        // Initialize Auth0 exactly like the documentation shows
        account = Auth0(
            context.getString(R.string.com_auth0_client_id),
            context.getString(R.string.com_auth0_domain)
        )
        authenticationApiClient = AuthenticationAPIClient(account)

        // Configure CredentialsManager for automatic token refresh
        val storage = SharedPreferencesStorage(context)
        credentialsManager = CredentialsManager(authenticationApiClient, storage)

        // Note: Auth0 Android SDK 2.11.0 doesn't have setMinTTL method
        // Token expiration handling will be done manually in our refresh logic
        Log.d("Auth0Manager", "CredentialsManager configured for automatic token management")

        // Debug logging
        Log.d("Auth0Manager", "Auth0 initialized successfully")
        Log.d("Auth0Manager", "Using scheme: app.whisperme.keyboard")

        // Check if user is already authenticated
        checkSessionBeforeUse()
    }

    private fun checkAuthentication() {
        if (localMode) { _isAuthenticated.value = LocalVoiceSession.active(localContext); return }
        if (credentialsManager.hasValidCredentials()) {
            credentialsManager.getCredentials(object : Callback<Credentials, CredentialsManagerException> {
                override fun onSuccess(result: Credentials) {
                    updateStateFromCredentials(
                        credentials = result,
                        fetchProfile = true,
                        notifyChange = false,
                    )
                }

                override fun onFailure(error: CredentialsManagerException) {
                    Log.w("Auth0Manager", "Failed to get credentials on startup: ${error.message}")
                    clearAuthenticationState(notifyChange = false)
                    // Don't notify here as this is initial state check, not a change
                }
            })
        } else {
            Log.d("Auth0Manager", "No valid credentials found on startup")
            clearAuthenticationState(notifyChange = false)
        }
    }

    fun refreshTokenOnAppStartup(callback: (Boolean) -> Unit) {
        if (localMode) { callback(LocalVoiceSession.active(localContext)); return }
        Log.d("Auth0Manager", "Attempting token refresh on app startup")
        if (!credentialsManager.hasValidCredentials()) {
            Log.d("Auth0Manager", "No credentials to refresh")
            callback(false)
            return
        }

        credentialsManager.getCredentials(object : Callback<Credentials, CredentialsManagerException> {
            override fun onSuccess(result: Credentials) {
                Log.d("Auth0Manager", "Token refreshed successfully on startup")
                updateStateFromCredentials(
                    credentials = result,
                    fetchProfile = true,
                    notifyChange = false,
                )
                callback(true)
            }

            override fun onFailure(error: CredentialsManagerException) {
                Log.w("Auth0Manager", "Token refresh failed on startup: ${error.message}")
                clearAuthenticationState(notifyChange = true)
                callback(false)
            }
        })
    }

    fun login(activity: ComponentActivity, callback: (Boolean, String?) -> Unit) {
        if (localMode) { callback(false, "Remove the local test session before signing in"); return }
        val packageName = activity.packageName
        val domain = activity.getString(R.string.com_auth0_domain)
        val scheme = "app.whisperme.keyboard"
        val expectedCallbackUrl = "$scheme://$domain/android/$packageName/callback"

        Log.d("Auth0Manager", "=== Auth0 Login Debug Info ===")
        Log.d("Auth0Manager", "Package Name: $packageName")
        Log.d("Auth0Manager", "Domain: $domain")
        Log.d("Auth0Manager", "Scheme: $scheme")
        Log.d("Auth0Manager", "Expected Callback URL: $expectedCallbackUrl")
        Log.d("Auth0Manager", "Starting login...")

        val audience = activity.getString(R.string.com_auth0_audience)

        val builder = WebAuthProvider.login(account)
            .withScheme(scheme)
            .withScope("openid profile email offline_access")
        if (audience.isNotBlank()) {
            builder.withAudience(audience)
        }

        builder.start(activity, object : Callback<Credentials, com.auth0.android.authentication.AuthenticationException> {
                override fun onSuccess(result: Credentials) {
                    Log.d("Auth0Manager", "Login successful!")
                    credentialsManager.saveCredentials(result)
                    updateStateFromCredentials(
                        credentials = result,
                        fetchProfile = true,
                        notifyChange = true,
                    )
                    callback(true, null)
                }

                override fun onFailure(error: com.auth0.android.authentication.AuthenticationException) {
                    Log.e("Auth0Manager", "Login failed: ${error.message}")
                    Log.e("Auth0Manager", "Error cause: ${error.cause}")
                    callback(false, error.message)
                }
            })
    }

    fun logout(activity: ComponentActivity, callback: (Boolean, String?) -> Unit) {
        WebAuthProvider.logout(account)
            .withScheme("app.whisperme.keyboard")
            .start(activity, object : Callback<Void?, com.auth0.android.authentication.AuthenticationException> {
                override fun onSuccess(result: Void?) {
                    credentialsManager.clearCredentials()
                    clearAuthenticationState(notifyChange = true)
                    callback(true, null)
                }

                override fun onFailure(error: com.auth0.android.authentication.AuthenticationException) {
                    // Even if logout fails, clear local credentials
                    credentialsManager.clearCredentials()
                    clearAuthenticationState(notifyChange = true)
                    callback(false, error.message)
                }
            })
    }

    private fun getUserProfile(accessToken: String) {
        Log.d("Auth0Manager", "Fetching user profile")

        authenticationApiClient.userInfo(accessToken)
            .start(object : Callback<UserProfile, com.auth0.android.authentication.AuthenticationException> {
                override fun onSuccess(result: UserProfile) {
                    Log.d("Auth0Manager", "User profile retrieved successfully")
                    Log.d("Auth0Manager", "User email: ${result.email}")
                    Log.d("Auth0Manager", "User name: ${result.name}")
                    _userProfile.value = result
                }

                override fun onFailure(error: com.auth0.android.authentication.AuthenticationException) {
                    Log.e("Auth0Manager", "Failed to get user profile: ${error.message}")
                    // Profile fetch failed, but user is still authenticated
                    _userProfile.value = null
                }
            })
    }

    fun getAuthHeader(): String? {
        return _accessToken.value?.let { "Bearer $it" }
    }

    fun invalidateSession() {
        credentialsManager.clearCredentials()
        clearAuthenticationState(notifyChange = true)
    }

    /**
     * Gets fresh credentials on-demand. The CredentialsManager automatically handles:
     * - Checking if current token is still valid
     * - Refreshing with refresh token if access token is expired/about to expire
     * - Token rotation if configured
     * This is the proper way to ensure fresh tokens before API calls.
     */
    fun getFreshCredentials(callback: (Boolean) -> Unit) {
        if (localMode) { callback(false); return }
        credentialsManager.getCredentials(object : Callback<Credentials, CredentialsManagerException> {
            override fun onSuccess(result: Credentials) {
                Log.d("Auth0Manager", "Fresh credentials obtained successfully")
                updateStateFromCredentials(
                    credentials = result,
                    fetchProfile = false,
                    notifyChange = true,
                )
                callback(true)
            }

            override fun onFailure(error: CredentialsManagerException) {
                Log.w("Auth0Manager", "Failed to get fresh credentials: ${error.message}")
                clearAuthenticationState(notifyChange = true)
                callback(false)
            }
        })
    }

    /**
     * Legacy method - kept for backward compatibility but delegates to getFreshCredentials
     */
    fun refreshTokenIfNeeded(callback: (Boolean) -> Unit) {
        getFreshCredentials(callback)
    }

    /**
     * Set a callback to be notified when authentication state changes.
     * This is useful for components that need to react to login/logout events.
     */
    fun setAuthStateChangeCallback(callback: (Boolean) -> Unit) {
        authStateChangeCallback = callback
    }

    /**
     * Clear the authentication state change callback.
     */
    fun clearAuthStateChangeCallback() {
        authStateChangeCallback = null
    }

    private fun notifyAuthStateChange(isAuthenticated: Boolean) {
        authStateChangeCallback?.invoke(isAuthenticated)
    }

    /**
     * Check if the current token is near expiration (within 10 minutes)
     */
    private fun isTokenNearExpiration(): Boolean {
        val expirationTime = _tokenExpirationTime.value ?: return false
        val currentTime = System.currentTimeMillis()
        val bufferTime = 10 * 60 * 1000 // 10 minutes in milliseconds
        return currentTime >= (expirationTime - bufferTime)
    }

    private fun shouldClearCredentials(error: CredentialsManagerException): Boolean {
        return sessionNeedsSignIn(error)
    }

    /**
     * Smart token refresh that only refreshes when needed.
     * Call this before making API requests to ensure fresh tokens.
     */
    suspend fun ensureFreshToken(): Boolean = getValidAccessToken() != null

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? {
        if (localMode) return null
        refreshMutex.lock()
        return try {
            val credentials = credentialsManager.awaitCredentials(null, 60, emptyMap(), forceRefresh)
            updateStateFromCredentials(credentials, fetchProfile = _userProfile.value == null, notifyChange = true)
            credentials.accessToken
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: CredentialsManagerException) {
            if (shouldClearCredentials(e) || sessionHasNoRenewableCredentials(e)) {
                clearAuthenticationState(notifyChange = true)
            } else {
                _sessionStatus.value = SessionStatus.RETRY
            }
            null
        } finally {
            refreshMutex.unlock()
        }
    }

    /** Backend acceptance is required before opening the microphone, even for unexpired JWTs. */
    suspend fun getBackendAccessToken(capabilitiesUrl: String = "https://process-voice.whisperme.app/capabilities"): String? {
        backendSessionMutex.lock()
        return try { checkBackendAccessToken(capabilitiesUrl) } finally { backendSessionMutex.unlock() }
    }

    private suspend fun checkBackendAccessToken(capabilitiesUrl: String): String? {
        var supportsInsertOnly = false
        backendSupportsHeartbeat = false
        backendSupportsResumeTranscript = false
        backendSupportsProcessTranscript = false
        return try {
            require(localMode || java.net.URI(capabilitiesUrl).scheme == "https") { "Backend authentication requires HTTPS" }
            val result = validateBackendSession(
                token = { force -> if (localMode) LocalVoiceSession.token(localContext) else getValidAccessToken(force) },
                accepts = { token -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val connection = java.net.URL(if (localMode) "http://127.0.0.1:8877/capabilities" else capabilitiesUrl)
                        .openConnection() as java.net.HttpURLConnection
                    try {
                        connection.connectTimeout = 4_000
                        connection.readTimeout = 4_000
                        connection.instanceFollowRedirects = false
                        connection.setRequestProperty("Authorization", "Bearer $token")
                        val status = connection.responseCode
                        if (status in 200..299) {
                            val body = connection.inputStream.bufferedReader().use { it.readText() }
                            supportsInsertOnly = backendAllowsInsertOnly(body)
                            val capabilities = org.json.JSONObject(body)
                            val features = capabilities.optJSONObject("features")
                            backendDefaultLlmProvider = capabilities.optJSONObject("defaults")?.optString("llmProvider")
                            _backendDefaultLlmModel.value = capabilities.optJSONObject("defaults")?.optString("llmModel")
                            backendSupportsHeartbeat = features?.optBoolean("connectionHeartbeat") == true
                            backendSupportsResumeTranscript = features?.optBoolean("resumeTranscript") == true
                            backendSupportsProcessTranscript = features?.optBoolean("processTranscript") == true
                        }
                        status
                    } finally { connection.disconnect() }
                } })
            when (result) {
                is BackendSession.Accepted -> { backendSupportsInsertOnly = supportsInsertOnly; _sessionStatus.value = SessionStatus.READY; result.token }
                BackendSession.Rejected -> { requireSignIn(); null }
                BackendSession.Retry -> { _sessionStatus.value = SessionStatus.RETRY; null }
                BackendSession.NoToken -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _sessionStatus.value = SessionStatus.RETRY
            throw e
        } catch (e: Exception) {
            _sessionStatus.value = SessionStatus.RETRY
            null
        }
    }

    /** A rejected access token must not destroy a potentially usable refresh token. */
    fun requireSignIn() {
        clearAuthenticationState(notifyChange = true)
    }
    companion object {
        @Volatile
        private var INSTANCE: Auth0Manager? = null

        fun getInstance(context: Context): Auth0Manager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Auth0Manager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
