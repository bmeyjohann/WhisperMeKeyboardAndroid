package app.whisperme.keyboard.app.account

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Server-owned access and metering. No local entitlement or onboarding defaults. */
@Serializable internal data class AccountEnvelope(val success: Boolean, val account: AccountDetails)
@Serializable internal data class AccountDetails(
    val id: String, val access: AccountAccess, val usage: AccountUsage,
    val subscription: AccountSubscription, val onboarding: AccountOnboarding,
    val canAdmin: Boolean, val checkoutAvailable: Boolean,
)
@Serializable internal data class AccountAccess(
    val state: String, val allowed: Boolean, val reason: String? = null,
    val planId: String? = null, val expiresAt: String? = null, val enforcementEnabled: Boolean,
)
@Serializable internal data class AccountUsage(
    val periodStart: String, val periodEnd: String, val audioSeconds: Double,
    val audioSecondsLimit: Double, val processingStarts: Long, val processingStartsLimit: Long,
) {
    val remainingAudioSeconds get() = (audioSecondsLimit - audioSeconds).coerceAtLeast(0.0)
    val remainingProcessingStarts get() = (processingStartsLimit - processingStarts).coerceAtLeast(0)
}
@Serializable internal data class AccountSubscription(val source: String, val status: String, val canManage: Boolean)
@Serializable internal data class AccountOnboarding(val version: Int, val completed: Boolean) {
    val currentCompleted get() = version >= 1 && completed
}
@Serializable internal data class PlansEnvelope(val success: Boolean, val plans: List<AccountPlan>)
@Serializable internal data class AccountPlan(
    val id: String, val name: String, val amountCents: Long, val currency: String,
    val interval: String, val audioSeconds: Double, val processingStarts: Long, val available: Boolean,
)
@Serializable internal data class OnboardingRequest(val version: Int, val completed: Boolean)
@Serializable internal data class FeedbackRequest(val submissionId: String, val kind: String, val message: String)
@Serializable private data class FeedbackResponse(val success: Boolean, val reportId: String)
@Serializable private data class SuccessResponse(val success: Boolean)
@Serializable private data class ErrorResponse(val error: String? = null, val code: String? = null)

internal class AccountServiceException(val status: Int, val code: String?, message: String) : Exception(message)

internal object AccountContract {
    private val json = Json { ignoreUnknownKeys = true }
    fun account(raw: String): AccountDetails = json.decodeFromString<AccountEnvelope>(raw).also {
        require(it.success)
        val account = it.account
        require(account.id.isNotBlank())
        val usage = account.usage
        require(usage.periodStart.isNotBlank() && usage.periodEnd.isNotBlank())
        require(usage.audioSeconds.isFinite() && usage.audioSeconds >= 0)
        require(usage.audioSecondsLimit.isFinite() && usage.audioSecondsLimit >= 0)
        require(usage.processingStarts >= 0 && usage.processingStartsLimit >= 0)
    }.account

    fun plans(raw: String): List<AccountPlan> = json.decodeFromString<PlansEnvelope>(raw).also {
        require(it.success)
        require(it.plans.map { plan -> plan.id }.distinct().size == it.plans.size)
        it.plans.forEach { plan ->
            require(plan.id.isNotBlank() && plan.name.isNotBlank() && plan.interval == "month")
            require(plan.amountCents >= 0 && plan.currency.matches(Regex("[a-zA-Z]{3}")))
            require(plan.audioSeconds.isFinite() && plan.audioSeconds >= 0 && plan.processingStarts >= 0)
        }
    }.plans

    fun onboardingBody(): String = json.encodeToString(OnboardingRequest(version = 1, completed = true))
    fun requireSuccess(raw: String) { require(json.decodeFromString<SuccessResponse>(raw).success) }
    fun feedbackBody(submissionId: String, kind: String, message: String): String {
        require(runCatching { java.util.UUID.fromString(submissionId).toString() == submissionId }.getOrDefault(false))
        require(kind in setOf("bug", "idea", "support"))
        require(message.trim().length in 1..4000) { "Write a message between 1 and 4,000 characters." }
        // Only typed text: deliberately no history, editor context, diagnostics, audio or credentials.
        return json.encodeToString(FeedbackRequest(submissionId, kind, message.trim()))
    }
    fun reportId(raw: String): String = json.decodeFromString<FeedbackResponse>(raw).also {
        require(it.success && it.reportId.isNotBlank())
    }.reportId

    fun failure(status: Int, raw: String): AccountServiceException {
        val code = runCatching { json.decodeFromString<ErrorResponse>(raw).code }.getOrNull()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_:-]{1,80}")) }
        val message = when (status) {
            401 -> "Your session could not be verified. Sign in again or retry your connection."
            403 -> "This account is not permitted to use this service."
            404, 405, 501 -> "This service is not available on the current server yet."
            409 -> "This request conflicts with an earlier submission. Review it and try again."
            429 -> "Too many requests. Please wait before retrying."
            503 -> "This service is unavailable or has not been configured yet. Please try later."
            in 500..599 -> "The service could not complete this request. Please try later."
            else -> "The request was not accepted. Review your input and try again."
        }
        // Never echo an arbitrary response body, HTML, reflected text or tokens into the UI.
        return AccountServiceException(status, code, message)
    }
}
