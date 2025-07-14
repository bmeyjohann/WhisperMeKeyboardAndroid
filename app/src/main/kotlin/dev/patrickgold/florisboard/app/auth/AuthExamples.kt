package dev.patrickgold.florisboard.app.auth

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/**
 * Example Composable showing how to integrate Auth0 authentication in your UI
 */
@Composable
fun AuthenticationExample() {
    val context = LocalContext.current
    val authManager = remember { Auth0Manager.getInstance(context) }
    
    val isAuthenticated by authManager.isAuthenticated.collectAsState()
    val userProfile by authManager.userProfile.collectAsState()
    val accessToken by authManager.accessToken.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isAuthenticated) {
            // User is logged in
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Welcome back!")
                    userProfile?.let { profile ->
                        Text("Email: ${profile.email}")
                        Text("Name: ${profile.name}")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            if (context is ComponentActivity) {
                                authManager.logout(context) { success, error ->
                                    if (!success) {
                                        // Handle logout error
                                        println("Logout failed: $error")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Logout")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Example of making an authenticated API call
            Button(
                onClick = {
                    makeAuthenticatedApiCall(authManager)
                }
            ) {
                Text("Make API Call")
            }
            
        } else {
            // User is not logged in
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Please log in to continue")
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            if (context is ComponentActivity) {
                                authManager.login(context) { success, error ->
                                    if (!success) {
                                        // Handle login error
                                        println("Login failed: $error")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Login")
                    }
                }
            }
        }
    }
}

/**
 * Example of making an authenticated API call using OkHttp
 */
private fun makeAuthenticatedApiCall(authManager: Auth0Manager) {
    val authHeader = authManager.getAuthHeader()
    if (authHeader == null) {
        println("No auth token available")
        return
    }

    // Example using OkHttp with the AuthInterceptor
    // In a real app, you'd set this up once in your Application class
    /*
    val httpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(context))
        .addInterceptor(AuthRefreshInterceptor(context))
        .build()

    val request = Request.Builder()
        .url("https://whisperme-app.com/api/voice-commands")
        .build()

    httpClient.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            println("Request failed: ${e.message}")
        }

        override fun onResponse(call: Call, response: Response) {
            println("Response: ${response.body?.string()}")
        }
    })
    */
    
    println("Would make API call with header: $authHeader")
}

/**
 * Helper function to check if user is authenticated before performing actions
 */
@Composable
fun RequireAuthentication(
    authManager: Auth0Manager,
    content: @Composable () -> Unit
) {
    val isAuthenticated by authManager.isAuthenticated.collectAsState()
    
    if (isAuthenticated) {
        content()
    } else {
        // Show login prompt or redirect to login
        AuthenticationExample()
    }
}

/**
 * Example usage of Auth0Manager in your Android keyboard application
 * 
 * This demonstrates how to integrate authentication into your app
 */
class AuthUsageExample {
    
    /**
     * Example: Login user
     */
    fun loginUser(activity: ComponentActivity) {
        val authManager = Auth0Manager.getInstance(activity)
        
        authManager.login(activity) { success, error ->
            if (success) {
                println("Login successful!")
                // Navigate to authenticated features
            } else {
                println("Login failed: $error")
                // Show error message to user
            }
        }
    }
    
    /**
     * Example: Logout user
     */
    fun logoutUser(activity: ComponentActivity) {
        val authManager = Auth0Manager.getInstance(activity)
        
        authManager.logout(activity) { success, error ->
            if (success) {
                println("Logout successful!")
                // Navigate to login screen
            } else {
                println("Logout failed: $error")
            }
        }
    }
    
    /**
     * Example: Check if user is authenticated
     */
    fun checkAuthentication(context: Context): Boolean {
        val authManager = Auth0Manager.getInstance(context)
        return authManager.isAuthenticated.value
    }
    
    /**
     * Example: Get access token for API calls
     */
    fun getAccessTokenForApiCalls(context: Context): String? {
        val authManager = Auth0Manager.getInstance(context)
        return authManager.accessToken.value
    }
    
    /**
     * Example: Make authenticated API call using OkHttp
     */
    fun makeAuthenticatedApiCall(context: Context) {
        // Set up OkHttp client with Auth interceptors (do this once in your app)
        /*
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(context))
            .addInterceptor(AuthRefreshInterceptor(context))
            .build()
        
        // Make your API request
        val request = Request.Builder()
            .url("https://whisperme-app.com/api/voice-processing")
            .post("your json data".toRequestBody("application/json".toMediaType()))
            .build()
        
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // Handle successful response
                val responseData = response.body?.string()
                println("API Response: $responseData")
            }
            
            override fun onFailure(call: Call, e: IOException) {
                // Handle network error
                println("API call failed: ${e.message}")
            }
        })
        */
    }
    
    /**
     * Example: Refresh token when needed
     */
    fun refreshTokenExample(context: Context) {
        val authManager = Auth0Manager.getInstance(context)
        
        authManager.refreshTokenIfNeeded { success ->
            if (success) {
                println("Token refreshed successfully")
                // Continue with authenticated operations
            } else {
                println("Token refresh failed - user may need to re-authenticate")
                // Redirect to login
            }
        }
    }
}

/**
 * Integration instructions:
 * 
 * 1. In your strings.xml, replace YOUR_AUTH0_CLIENT_ID with your actual Auth0 client ID
 * 
 * 2. In your main Activity, initialize Auth0 by calling:
 *    Auth0Manager.getInstance(this)
 * 
 * 3. For API calls that require authentication, use:
 *    - The AuthInterceptor in your OkHttp client
 *    - The AuthRefreshInterceptor to handle token refresh automatically
 * 
 * 4. Listen to authentication state changes:
 *    authManager.isAuthenticated.collect { isAuthenticated ->
 *        if (isAuthenticated) {
 *            // User is logged in
 *        } else {
 *            // User is not logged in
 *        }
 *    }
 * 
 * 5. For voice processing API calls, ensure you include the Authorization header:
 *    val authHeader = authManager.getAuthHeader()
 */ 