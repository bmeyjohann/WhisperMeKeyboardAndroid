package app.whisperme.keyboard.app.account

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AccountContractTest {
    private val account = """{
      "success":true,"account":{"id":"auth0|one",
      "access":{"state":"trial","allowed":true,"enforcementEnabled":false,"planId":"basic"},
      "usage":{"periodStart":"2026-10-01T00:00:00Z","periodEnd":"2026-11-01T00:00:00Z",
        "audioSeconds":61.5,"audioSecondsLimit":600,"processingStarts":2,"processingStartsLimit":10},
      "subscription":{"source":"none","status":"none","canManage":false},
      "onboarding":{"version":1,"completed":false},"canAdmin":false,"checkoutAvailable":false}}
    """.trimIndent()
    private fun update(section: String, field: String, value: JsonElement?): String {
        val envelope = Json.parseToJsonElement(account).jsonObject
        val details = envelope.getValue("account").jsonObject
        val part = details.getValue(section).jsonObject.toMutableMap()
        if (value == null) part.remove(field) else part[field] = value
        return JsonObject(envelope + ("account" to JsonObject(details + (section to JsonObject(part))))).toString()
    }

    @Test fun readsFrozenContractWithoutInventingEntitlementsOrOnboarding() {
        val value = AccountContract.account(account)
        assertEquals("auth0|one", value.id)
        assertTrue(value.access.allowed)
        assertFalse(value.access.enforcementEnabled)
        assertFalse(value.onboarding.currentCompleted)
        assertNull(value.access.expiresAt)
        assertEquals(538.5, value.usage.remainingAudioSeconds)
        assertEquals(8, value.usage.remainingProcessingStarts)
    }

    @Test fun forwardCompatibleUnknownCostDoesNotBecomeAZeroEstimate() {
        val expanded = account.replace("\"checkoutAvailable\":false", "\"checkoutAvailable\":false,\"costs\":{\"knownUsd\":null},\"newFlag\":true")
        assertEquals(AccountContract.account(account), AccountContract.account(expanded))
        assertFalse(AccountContract.account(expanded).checkoutAvailable)
    }

    @Test fun missingAndInvalidUsageFailsRatherThanDisplayingZero() {
        assertThrows(Exception::class.java) { AccountContract.account(update("usage", "audioSeconds", null)) }
        assertThrows(Exception::class.java) { AccountContract.account(update("usage", "audioSeconds", JsonNull)) }
        assertThrows(Exception::class.java) { AccountContract.account(update("usage", "audioSeconds", JsonPrimitive(-1))) }
        assertThrows(Exception::class.java) { AccountContract.account(update("usage", "processingStarts", JsonPrimitive(1.5))) }
        assertThrows(Exception::class.java) { AccountContract.account(update("access", "allowed", null)) }
        assertThrows(Exception::class.java) { AccountContract.account("""{"success":false,"error":"failed"}""") }
    }

    @Test fun overBudgetAndFutureAccessStatesRemainServerOwned() {
        val over = AccountContract.account(update("usage", "audioSeconds", JsonPrimitive(900)))
        assertEquals(0.0, over.usage.remainingAudioSeconds)
        assertTrue(over.access.allowed) // Enforcement is off; do not infer denial from quota.
        assertEquals("future_state", AccountContract.account(update("access", "state", JsonPrimitive("future_state"))).access.state)
        assertFalse(AccountContract.account(update("access", "allowed", JsonPrimitive(false))).access.allowed)
    }

    @Test fun onboardingIsAnExplicitVersionedServerWriteAndRequiresSuccess() {
        assertFalse(AccountOnboarding(0, true).currentCompleted)
        assertFalse(AccountOnboarding(1, false).currentCompleted)
        assertTrue(AccountOnboarding(1, true).currentCompleted)
        assertTrue(AccountOnboarding(2, true).currentCompleted)
        assertEquals(Json.parseToJsonElement("""{"version":1,"completed":true}"""), Json.parseToJsonElement(AccountContract.onboardingBody()))
        AccountContract.requireSuccess("""{"success":true}""")
        assertThrows(Exception::class.java) { AccountContract.requireSuccess("""{"success":false}""") }
    }

    @Test fun safePublicPlansDoNotRequireOrRetainStripeIds() {
        val raw = """{"success":true,"plans":[{"id":"basic","name":"Basic","amountCents":900,"currency":"eur","interval":"month","audioSeconds":600,"processingStarts":50,"available":false,"stripePriceId":"must_not_be_used"}]}"""
        val plans = AccountContract.plans(raw)
        assertEquals("Basic", plans.single().name)
        assertFalse(plans.single().available)
        assertThrows(Exception::class.java) { AccountContract.plans(raw.replace("\"month\"", "\"week\"")) }
        assertThrows(Exception::class.java) { AccountContract.plans(raw.replace("900", "-1")) }
    }

    @Test fun errorsHandleUnavailableUnsupportedAuthAndMalformedBodiesSafely() {
        assertTrue(AccountContract.failure(503, """{"success":false,"error":"Bearer secret","code":"billing_not_configured"}""").message!!.contains("configured"))
        assertEquals("billing_not_configured", AccountContract.failure(503, """{"code":"billing_not_configured"}""").code)
        assertTrue(AccountContract.failure(404, "<html>private server details</html>").message!!.contains("not available"))
        assertTrue(AccountContract.failure(401, "{}").message!!.contains("Sign in"))
        assertTrue(AccountContract.failure(429, "{}").message!!.contains("wait"))
        assertNull(AccountContract.failure(500, """{"code":"Bearer secret"}""").code)
        assertFalse(AccountContract.failure(400, """{"error":"secret transcript"}""").message!!.contains("transcript"))
    }

    @Test fun feedbackHasExactlyThreeFieldsAndRetriesKeepTheSubmissionId() {
        val id = "76eea254-2d18-42cf-bd34-a217bbd06b22"
        val body = AccountContract.feedbackBody(id, "bug", "  Keyboard stops responding. Grüße\nPlease help.  ")
        val fields = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("submissionId", "kind", "message"), fields.keys)
        assertEquals(id, fields.getValue("submissionId").jsonPrimitive.content)
        assertEquals("Keyboard stops responding. Grüße\nPlease help.", fields.getValue("message").jsonPrimitive.content)
        assertEquals(body, AccountContract.feedbackBody(id, "bug", "Keyboard stops responding. Grüße\nPlease help."))
        assertEquals("report-1", AccountContract.reportId("""{"success":true,"reportId":"report-1"}"""))
        assertThrows(Exception::class.java) { AccountContract.reportId("""{"success":true}""") }
        assertThrows(Exception::class.java) { AccountContract.feedbackBody(id, "bug", " ") }
        assertThrows(Exception::class.java) { AccountContract.feedbackBody(id, "bug", "x".repeat(4001)) }
        assertThrows(Exception::class.java) { AccountContract.feedbackBody(id, "transcript", "text") }
        assertThrows(Exception::class.java) { AccountContract.feedbackBody("bad-id", "bug", "text") }
    }
}
