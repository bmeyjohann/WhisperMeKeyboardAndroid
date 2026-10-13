package app.whisperme.keyboard.app.account

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Intercepted requests never open a socket or call Auth0/providers. */
class AccountApiTest {
    private fun client(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (status, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun authenticationRetriesOnceAndPreservesExplicitFeedbackBody() = runBlocking {
        val requests = mutableListOf<Request>()
        val refreshes = mutableListOf<Boolean>()
        val api = AccountApi({ force -> refreshes += force; if (force) "renewed" else "first" }, false, client {
            requests += it
            if (requests.size == 1) 401 to "{}" else 200 to """{"success":true,"reportId":"saved"}"""
        })
        assertEquals("saved", api.feedback("76eea254-2d18-42cf-bd34-a217bbd06b22", "support", "Help with setup"))
        assertEquals(listOf(false, true), refreshes)
        assertEquals(listOf("Bearer first", "Bearer renewed"), requests.map { it.header("Authorization") })
        assertEquals(listOf("/feedback", "/feedback"), requests.map { it.url.encodedPath })
        val bodies = requests.map { request -> Buffer().also { request.body!!.writeTo(it) }.readUtf8() }
        assertEquals(bodies.first(), bodies.last())
        assertEquals(setOf("submissionId", "kind", "message"), Json.parseToJsonElement(bodies.first()).jsonObject.keys)
    }

    @Test fun unsupportedAndUnconfiguredServicesDoNotLoopOrRefreshCredentials() = runBlocking {
        listOf(404, 503).forEach { status ->
            var requests = 0
            val refreshes = mutableListOf<Boolean>()
            val api = AccountApi({ force -> refreshes += force; "valid" }, false, client {
                requests++; status to """{"success":false,"error":"internal details"}"""
            })
            val failure = runCatching { api.account() }.exceptionOrNull()
            assertTrue(failure is AccountServiceException)
            assertEquals(status, (failure as AccountServiceException).status)
            assertEquals(1, requests)
            assertEquals(listOf(false), refreshes)
        }
    }

    @Test fun localTestingNeverSendsAccountOrFeedbackRequests() = runBlocking {
        var tokenCalls = 0
        var requests = 0
        val api = AccountApi({ tokenCalls++; "local-secret" }, true, client { requests++; 200 to "{}" })
        val failure = runCatching { api.account() }.exceptionOrNull() as AccountServiceException
        assertEquals("local_test", failure.code)
        assertTrue(runCatching { api.feedback("76eea254-2d18-42cf-bd34-a217bbd06b22", "bug", "Help") }.isFailure)
        assertEquals(0, tokenCalls)
        assertEquals(0, requests)
    }

    @Test fun finishIsTheOnlyVersionedWriteAndReadDoesNotPost() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = AccountApi({ "valid" }, false, client {
            requests += it
            200 to if (it.method == "GET") """{"success":true,"plans":[]}""" else """{"success":true}"""
        })
        assertTrue(api.plans().isEmpty())
        assertEquals(1, requests.size)
        assertEquals("GET", requests.single().method)
        api.finishOnboarding()
        assertEquals("POST", requests.last().method)
        assertEquals("/account/onboarding", requests.last().url.encodedPath)
        assertEquals(Json.parseToJsonElement("""{"version":1,"completed":true}"""),
            Json.parseToJsonElement(Buffer().also { requests.last().body!!.writeTo(it) }.readUtf8()))
    }

    @Test fun malformedSuccessAndOversizedBodiesAreNotReportedAsSaved() = runBlocking {
        listOf("{}", """{"success":false}""", "x".repeat(262_145)).forEach { body ->
            val api = AccountApi({ "valid" }, false, client { 200 to body })
            assertTrue(runCatching { api.finishOnboarding() }.exceptionOrNull() is AccountServiceException)
        }
    }
}
