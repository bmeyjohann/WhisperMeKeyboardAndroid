package dev.patrickgold.florisboard.app.auth

import android.content.Context
import android.content.Intent
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
        account = Auth0(context)
        authenticationApiClient = AuthenticationAPIClient(account)
        credentialsManager = CredentialsManager(authenticationApiClient, SharedPreferencesStorage(context))
        
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
        WebAuthProvider.login(account)
            .withScheme("app.whisperme.keyboard")
            .withScope("openid profile email offline_access")
            .withAudience("https://whisperme-app.com/api")
            .start(activity, object : Callback<Credentials, com.auth0.android.authentication.AuthenticationException> {
                override fun onSuccess(result: Credentials) {
                    credentialsManager.saveCredentials(result)
                    _isAuthenticated.value = true
                    _accessToken.value = result.accessToken
                    getUserProfile(result.accessToken)
                    callback(true, null)
                }

                override fun onFailure(error: com.auth0.android.authentication.AuthenticationException) {
                    callback(false, error.message)
                }
            })
    }

    fun logout(activity: ComponentActivity, callback: (Boolean, String?) -> Unit) {
        WebAuthProvider.logout(account)
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
        authenticationApiClient.userInfo(accessToken)
            .start(object : Callback<UserProfile, com.auth0.android.authentication.AuthenticationException> {
                override fun onSuccess(result: UserProfile) {
                    _userProfile.value = result
                }

                override fun onFailure(error: com.auth0.android.authentication.AuthenticationException) {
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