package dev.patrickgold.florisboard.app.auth

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val context: Context) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val authManager = Auth0Manager.getInstance(context)
        
        // Get the access token
        val accessToken = authManager.accessToken.value
        
        return if (accessToken != null) {
            // Add the Authorization header
            val authenticatedRequest = request.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
            chain.proceed(authenticatedRequest)
        } else {
            // Proceed without authentication header
            chain.proceed(request)
        }
    }
}

class AuthRefreshInterceptor(private val context: Context) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        
        // If we get a 401, try to refresh the token
        if (response.code == 401) {
            val authManager = Auth0Manager.getInstance(context)
            
            return runBlocking {
                var refreshSuccess = false
                authManager.refreshTokenIfNeeded { success ->
                    refreshSuccess = success
                }
                
                if (refreshSuccess) {
                    // Retry the request with the new token
                    val newAccessToken = authManager.accessToken.value
                    val newRequest = request.newBuilder()
                        .header("Authorization", "Bearer $newAccessToken")
                        .build()
                    chain.proceed(newRequest)
                } else {
                    response
                }
            }
        }
        
        return response
    }
} 