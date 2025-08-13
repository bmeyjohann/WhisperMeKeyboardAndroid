/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings.voiceinput

import android.content.Context
import dev.patrickgold.florisboard.app.auth.Auth0Manager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TagRulesResponse(
    val success: Boolean,
    val rules: List<TagRule>
)

@Serializable
data class AppAssociation(val app_name: String, val app_platform: String)

@Serializable
data class TagRuleRequest(
    val name: String,
    val rule_text: String,
    val apps: List<AppAssociation>? = null
)

@Serializable
data class TagRule(
    val id: Int,
    val user_id: String,
    val name: String,
    val rule_text: String,
    val created_ts: String,
    val updated_ts: String,
    val created_by: String,
    val updated_by: String,
    val apps: List<AppAssociation>? = null
)

class TagRulesApiService(private val context: Context) {
    private val authManager = Auth0Manager.getInstance(context)
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    
    private val baseUrl = "https://rules-management.whisperme.app"
    
    suspend fun getTagRules(): Flow<Result<List<TagRule>>> = flow {
        try {
            println("DEBUG: Checking authentication...")
            val authHeader = authManager.getAuthHeader()
            println("DEBUG: Auth header: ${authHeader?.take(20)}...")
            
            val token = authHeader?.removePrefix("Bearer ")
            if (token == null) {
                println("DEBUG: No auth token available")
                emit(Result.failure(Exception("Not authenticated")))
                return@flow
            }
            
            println("DEBUG: Making API request to $baseUrl/api/tag-rules")
            println("DEBUG: Token: ${token.take(10)}...")
            
            val request = Request.Builder()
                .url("$baseUrl/api/tag-rules")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                println("DEBUG: Response code: ${response.code}")
                println("DEBUG: Response headers: ${response.headers}")
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    println("DEBUG: Response body: $responseBody")
                    
                    if (responseBody != null) {
                        try {
                            val responseObj = json.decodeFromString<TagRulesResponse>(responseBody)
                            if (responseObj.success) {
                                println("DEBUG: Successfully parsed ${responseObj.rules.size} rules")
                                emit(Result.success(responseObj.rules))
                            } else {
                                emit(Result.failure(Exception("API returned success=false")))
                            }
                        } catch (e: Exception) {
                            println("DEBUG: JSON parsing error: ${e.message}")
                            emit(Result.failure(Exception("Failed to parse response: ${e.message}")))
                        }
                    } else {
                        println("DEBUG: Empty response body")
                        emit(Result.failure(Exception("Empty response")))
                    }
                } else {
                    val errorBody = response.body?.string()
                    println("DEBUG: Error response body: $errorBody")
                    
                    val error = if (errorBody != null) {
                        try {
                            json.decodeFromString<ApiError>(errorBody).error
                        } catch (e: Exception) {
                            "HTTP ${response.code}"
                        }
                    } else {
                        "HTTP ${response.code}"
                    }
                    println("DEBUG: Final error message: $error")
                    emit(Result.failure(Exception(error)))
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Exception in getTagRules: ${e.message}")
            e.printStackTrace()
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)
    
    suspend fun createTagRule(request: TagRuleRequest): Flow<Result<TagRule>> = flow {
        try {
            val token = authManager.getAuthHeader()?.removePrefix("Bearer ")
            if (token == null) {
                emit(Result.failure(Exception("Not authenticated")))
                return@flow
            }
            
            val jsonBody = json.encodeToString(TagRuleRequest.serializer(), request)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            
            val httpRequest = Request.Builder()
                .url("$baseUrl/api/tag-rules")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()
            
            client.newCall(httpRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val rule = json.decodeFromString<TagRule>(responseBody)
                        emit(Result.success(rule))
                    } else {
                        emit(Result.failure(Exception("Empty response")))
                    }
                } else {
                    val errorBody = response.body?.string()
                    val error = if (errorBody != null) {
                        try {
                            json.decodeFromString<ApiError>(errorBody).error
                        } catch (e: Exception) {
                            "HTTP ${response.code}"
                        }
                    } else {
                        "HTTP ${response.code}"
                    }
                    emit(Result.failure(Exception(error)))
                }
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)
    
    suspend fun updateTagRule(id: Int, request: TagRuleRequest): Flow<Result<TagRule>> = flow {
        try {
            val token = authManager.getAuthHeader()?.removePrefix("Bearer ")
            if (token == null) {
                emit(Result.failure(Exception("Not authenticated")))
                return@flow
            }
            
            val jsonBody = json.encodeToString(TagRuleRequest.serializer(), request)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            
            val httpRequest = Request.Builder()
                .url("$baseUrl/api/tag-rules/$id")
                .addHeader("Authorization", "Bearer $token")
                .put(requestBody)
                .build()
            
            client.newCall(httpRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val rule = json.decodeFromString<TagRule>(responseBody)
                        emit(Result.success(rule))
                    } else {
                        emit(Result.failure(Exception("Empty response")))
                    }
                } else {
                    val errorBody = response.body?.string()
                    val error = if (errorBody != null) {
                        try {
                            json.decodeFromString<ApiError>(errorBody).error
                        } catch (e: Exception) {
                            "HTTP ${response.code}"
                        }
                    } else {
                        "HTTP ${response.code}"
                    }
                    emit(Result.failure(Exception(error)))
                }
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)
    
    suspend fun deleteTagRule(id: Int): Flow<Result<Unit>> = flow {
        try {
            val token = authManager.getAuthHeader()?.removePrefix("Bearer ")
            if (token == null) {
                emit(Result.failure(Exception("Not authenticated")))
                return@flow
            }
            
            val request = Request.Builder()
                .url("$baseUrl/api/tag-rules/$id")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    emit(Result.success(Unit))
                } else {
                    val errorBody = response.body?.string()
                    val error = if (errorBody != null) {
                        try {
                            json.decodeFromString<ApiError>(errorBody).error
                        } catch (e: Exception) {
                            "HTTP ${response.code}"
                        }
                    } else {
                        "HTTP ${response.code}"
                    }
                    emit(Result.failure(Exception(error)))
                }
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun testApiConnection(): Flow<Result<String>> = flow {
        try {
            println("DEBUG: Testing API connection to $baseUrl")
            
            val request = Request.Builder()
                .url("$baseUrl/")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                println("DEBUG: Test response code: ${response.code}")
                val responseBody = response.body?.string()
                println("DEBUG: Test response body: $responseBody")
                
                if (response.isSuccessful) {
                    emit(Result.success("API is reachable"))
                } else {
                    emit(Result.failure(Exception("API returned ${response.code}")))
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Test connection failed: ${e.message}")
            e.printStackTrace()
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)
} 