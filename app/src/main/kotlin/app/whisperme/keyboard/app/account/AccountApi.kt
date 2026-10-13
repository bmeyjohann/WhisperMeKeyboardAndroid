package app.whisperme.keyboard.app.account

import android.content.Context
import app.whisperme.keyboard.app.auth.Auth0Manager
import app.whisperme.keyboard.app.auth.LocalVoiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Separate from the dictation session path. Account outages never gate the keyboard. */
internal class AccountApi(
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
    private val localMode: Boolean,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    constructor(context: Context) : this(
        tokenProvider = { force -> Auth0Manager.getInstance(context).getValidAccessToken(force) },
        localMode = LocalVoiceSession.configured(context),
    )

    suspend fun account() = decode { AccountContract.account(request("account")) }
    suspend fun plans() = decode { AccountContract.plans(request("plans")) }
    suspend fun finishOnboarding() = decode {
        AccountContract.requireSuccess(request("account/onboarding", AccountContract.onboardingBody()))
    }
    suspend fun feedback(id: String, kind: String, message: String): String {
        val body = AccountContract.feedbackBody(id, kind, message)
        return decode { AccountContract.reportId(request("feedback", body)) }
    }

    private suspend fun <T> decode(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AccountServiceException) { throw failure }
    catch (_: java.io.IOException) { throw AccountServiceException(0, null, "Could not reach the service. Check your connection and retry. Your local history is still available.") }
    catch (_: Exception) { throw AccountServiceException(0, null, "The server returned an unsupported response. Please retry later or contact support.") }

    private suspend fun request(path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        check(path in setOf("account", "plans", "account/onboarding", "feedback"))
        if (localMode) throw AccountServiceException(0, "local_test", "Account and feedback services are disabled during local voice testing.")
        withTimeout(45_000) {
            var token = tokenProvider(false) ?: throw AccountContract.failure(401, "{}")
            repeat(2) { attempt ->
                val request = Request.Builder().url("https://process-voice.whisperme.app/$path")
                    .header("Authorization", "Bearer $token").header("Accept", "application/json")
                    .method(if (body == null) "GET" else "POST", body?.toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 401 && attempt == 0) {
                        token = tokenProvider(true) ?: throw AccountContract.failure(401, "{}")
                    } else {
                        val source = response.body?.source()
                        if (source?.request(262_145) == true) throw AccountServiceException(0, null, "The server response was too large. Please contact support.")
                        val raw = source?.readUtf8().orEmpty()
                        if (!response.isSuccessful) throw AccountContract.failure(response.code, raw)
                        return@withTimeout raw
                    }
                }
            }
            throw AccountContract.failure(401, "{}")
        }
    }
}
