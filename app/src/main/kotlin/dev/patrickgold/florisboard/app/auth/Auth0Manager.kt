package dev.patrickgold.florisboard.app.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.patrickgold.florisboard.R

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

    init {
        // Initialize Auth0 exactly like the documentation shows
        account = Auth0(
            context.getString(R.string.com_auth0_client_id),
            context.getString(R.string.com_auth0_domain)
        )
        authenticationApiClient = AuthenticationAPIClient(account)
        credentialsManager = CredentialsManager(authenticationApiClient, SharedPreferencesStorage(context))
        
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
                    _isAuthenticated.value = true
                    _accessToken.value = result.accessToken
                    getUserProfile(result.accessToken)
                }

                override fun onFailure(error: CredentialsManagerException) {
                    _isAuthenticated.value = false
                    _accessToken.value = null
                    _userProfile.value = null
                }
            })
        }
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
        
        WebAuthProvider.login(account)
            .withScheme(scheme)
            .withScope("openid profile email")
            .start(activity, object : Callback<Credentials, com.auth0.android.authentication.AuthenticationException> {
                override fun onSuccess(result: Credentials) {
                    Log.d("Auth0Manager", "Login successful!")
                    credentialsManager.saveCredentials(result)
                    _isAuthenticated.value = true
                    _accessToken.value = result.accessToken
                    getUserProfile(result.accessToken)
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
                    _isAuthenticated.value = false
                    _accessToken.value = null
                    _userProfile.value = null
                    callback(true, null)
                }

                override fun onFailure(error: com.auth0.android.authentication.AuthenticationException) {
                    // Even if logout fails, clear local credentials
                    credentialsManager.clearCredentials()
                    _isAuthenticated.value = false
                    _accessToken.value = null
                    _userProfile.value = null
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

    fun refreshTokenIfNeeded(callback: (Boolean) -> Unit) {
        if (!credentialsManager.hasValidCredentials()) {
            callback(false)
            return
        }

        credentialsManager.getCredentials(object : Callback<Credentials, CredentialsManagerException> {
            override fun onSuccess(result: Credentials) {
                _accessToken.value = result.accessToken
                callback(true)
            }

            override fun onFailure(error: CredentialsManagerException) {
                _isAuthenticated.value = false
                _accessToken.value = null
                _userProfile.value = null
                callback(false)
            }
        })
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