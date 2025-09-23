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
import app.whisperme.keyboard.R

class Auth0Manager private constructor(context: Context) {
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
    private var refreshInProgress = false

    // Callback for when authentication state changes
    private var authStateChangeCallback: ((Boolean) -> Unit)? = null

    private fun updateStateFromCredentials(
        credentials: Credentials,
        fetchProfile: Boolean,
        notifyChange: Boolean,
    ) {
        val wasAuthenticated = _isAuthenticated.value
        _isAuthenticated.value = true
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
        checkAuthentication()
    }

    private fun checkAuthentication() {
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
        val message = error.message?.lowercase() ?: return false
        return message.contains("refresh_token") ||
            message.contains("unauthorized") ||
            message.contains("invalid_grant") ||
            message.contains("no credentials")
    }

    /**
     * Smart token refresh that only refreshes when needed.
     * Call this before making API requests to ensure fresh tokens.
     */
    suspend fun ensureFreshToken(): Boolean {
        if (refreshInProgress) {
            Log.d("Auth0Manager", "Token refresh already in progress, waiting...")
            // Wait a bit and check again (simple debouncing)
            kotlinx.coroutines.delay(100)
            return _accessToken.value != null
        }

        val hasStoredCredentials = credentialsManager.hasValidCredentials()
        val tokenMissing = _accessToken.value.isNullOrEmpty()
        val expirationTime = _tokenExpirationTime.value
        val needsRefresh = tokenMissing || !hasStoredCredentials || expirationTime == null || isTokenNearExpiration()

        return if (needsRefresh) {
            Log.d("Auth0Manager", "Refreshing credentials via CredentialsManager")
            refreshTokenSafely(fetchProfile = false)
        } else {
            Log.d("Auth0Manager", "Token is still valid, no refresh needed")
            true
        }
    }

    suspend fun getValidAccessToken(): String? {
        return if (ensureFreshToken()) {
            _accessToken.value
        } else {
            null
        }
    }

    /**
     * Thread-safe token refresh with proper error handling
     */
    private suspend fun refreshTokenSafely(fetchProfile: Boolean): Boolean {
        if (refreshInProgress) {
            Log.d("Auth0Manager", "Refresh already running, waiting...")
            kotlinx.coroutines.delay(100)
            return _accessToken.value != null
        }

        refreshInProgress = true
        Log.d("Auth0Manager", "Starting token refresh...")

        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                credentialsManager.getCredentials(object : Callback<Credentials, CredentialsManagerException> {
                    override fun onSuccess(result: Credentials) {
                        Log.d("Auth0Manager", "Token refresh successful")
                        updateStateFromCredentials(
                            credentials = result,
                            fetchProfile = fetchProfile,
                            notifyChange = true,
                        )
                        refreshInProgress = false
                        continuation.resumeWith(Result.success(true))
                    }

                    override fun onFailure(error: CredentialsManagerException) {
                        Log.w("Auth0Manager", "Token refresh failed: ${error.message}")

                        if (shouldClearCredentials(error)) {
                            Log.d("Auth0Manager", "Refresh token invalid, clearing credentials")
                            clearAuthenticationState(notifyChange = true)
                            credentialsManager.clearCredentials()
                        }

                        refreshInProgress = false
                        continuation.resumeWith(Result.success(false))
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("Auth0Manager", "Exception during token refresh", e)
            refreshInProgress = false
            false
        }
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
