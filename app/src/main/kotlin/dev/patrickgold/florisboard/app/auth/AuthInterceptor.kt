package dev.patrickgold.florisboard.app.auth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val context: Context) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val authManager = Auth0Manager.getInstance(context)
        
        return runBlocking {
            // Proactively ensure we have a fresh token before making the request
            val hasValidToken = authManager.ensureFreshToken()
            
            if (hasValidToken) {
                val accessToken = authManager.accessToken.value
                if (accessToken != null) {
                    Log.d("AuthInterceptor", "Adding auth header with fresh token")
                    val authenticatedRequest = request.newBuilder()
                        .header("Authorization", "Bearer $accessToken")
                        .build()
                    chain.proceed(authenticatedRequest)
                } else {
                    Log.w("AuthInterceptor", "Token refresh succeeded but no token available")
                    chain.proceed(request)
                }
            } else {
                Log.w("AuthInterceptor", "Cannot obtain valid token, proceeding without auth")
                chain.proceed(request)
            }
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
                val refreshSuccess = authManager.ensureFreshToken()
                
                if (refreshSuccess) {
                    Log.d("AuthRefreshInterceptor", "Token refreshed successfully, retrying request")
                    // Retry the request with the new token
                    val newAccessToken = authManager.accessToken.value
                    val newRequest = request.newBuilder()
                        .header("Authorization", "Bearer $newAccessToken")
                        .build()
                    chain.proceed(newRequest)
                } else {
                    Log.w("AuthRefreshInterceptor", "Token refresh failed, returning original 401 response")
                    response
                }
            }
        }
        
        return response
    }
} 