package ru.privatediary.monetization

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class PaymentFailure { NOT_CONFIGURED, CONNECTION, SERVER, INVALID_RESPONSE }

class PaymentException(val reason: PaymentFailure) : Exception("Payment request failed: $reason")

data class PaymentRequest(val method: String, val url: String, val body: String? = null, val bearerToken: String? = null) {
    override fun toString() = "PaymentRequest(method=$method, url=<redacted>, body=<redacted>, bearerToken=<redacted>)"
}

data class PaymentResponse(val statusCode: Int, val body: String) {
    override fun toString() = "PaymentResponse(statusCode=$statusCode, body=<redacted>)"
}

interface PaymentTransport {
    suspend fun execute(request: PaymentRequest): PaymentResponse
    fun cancelActiveRequests() = Unit
}

/** No SDK, polling, startup requests or provider credentials. Call only after an explicit user action. */
class SbpCheckoutClient(
    baseUrl: String,
    networkEnabled: Boolean,
    private val transport: PaymentTransport = UrlConnectionPaymentTransport(),
) {
    private val endpoint = runCatching {
        val uri = URI(baseUrl)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port == 443)
        require(!baseUrl.any { it.isISOControl() })
        baseUrl.trimEnd('/')
    }.getOrNull()
    val isConfigured: Boolean = networkEnabled && endpoint != null

    suspend fun createCheckout(identity: CheckoutIdentity): CheckoutSession {
        val payload = JSONObject().put("installationId", identity.installationId)
            .put("accountToken", identity.accountToken).put("requestId", identity.requestId)
            .put("planId", "pro_monthly")
        return parseResponse(request("POST", "/v1/checkout", payload.toString())) { json ->
            require(json.getString("amount") == "99.00")
            require(json.getString("currency") == "RUB" && json.getString("period") == "month")
            CheckoutSession(json.getString("checkoutId"), json.getString("paymentUrl"))
        }
    }

    suspend fun checkPayment(identity: CheckoutIdentity, checkoutId: String): PaymentStatusResult {
        require(isCanonicalUuid(checkoutId))
        val response = request("GET", "/v1/payments/$checkoutId?installationId=${identity.installationId}", bearer = identity.accountToken)
        return parseResponse(response) { json ->
            val status = when (json.getString("status")) {
                "pending" -> PaymentStatus.PENDING
                "paid" -> PaymentStatus.PAID
                "canceled" -> PaymentStatus.CANCELED
                else -> error("Unknown payment status")
            }
            PaymentStatusResult(status, readEntitlement(json))
        }
    }

    suspend fun restore(identity: CheckoutIdentity): String? {
        val payload = JSONObject().put("installationId", identity.installationId).put("accountToken", identity.accountToken)
        return parseResponse(request("POST", "/v1/restore", payload.toString())) { readEntitlement(it) }
    }

    fun cancelActiveRequests() = transport.cancelActiveRequests()

    private suspend fun request(method: String, path: String, body: String? = null, bearer: String? = null): PaymentResponse {
        if (!isConfigured) throw PaymentException(PaymentFailure.NOT_CONFIGURED)
        val response = transport.execute(PaymentRequest(method, endpoint + path, body, bearer))
        if (response.statusCode !in 200..299) throw PaymentException(PaymentFailure.SERVER)
        return response
    }

    private inline fun <T> parseResponse(response: PaymentResponse, parse: (JSONObject) -> T): T = try {
        require(response.body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
        parse(JSONObject(response.body))
    } catch (_: Exception) {
        throw PaymentException(PaymentFailure.INVALID_RESPONSE)
    }

    private fun readEntitlement(json: JSONObject): String? {
        require(json.has("entitlement"))
        if (json.isNull("entitlement")) return null
        return json.getString("entitlement").also { require(it.length in 1..8192) }
    }
}

private const val MAX_RESPONSE_BYTES = 32 * 1024

/** HTTPS platform transport. Redirects are disabled so bearer tokens never follow another origin. */
class UrlConnectionPaymentTransport : PaymentTransport {
    private val active = Collections.newSetFromMap(ConcurrentHashMap<HttpURLConnection, Boolean>())

    override suspend fun execute(request: PaymentRequest): PaymentResponse = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        var connection: HttpURLConnection? = null
        try {
            val url = URL(request.url)
            require(url.protocol == "https" && url.userInfo == null)
            val opened = url.openConnection() as HttpURLConnection
            connection = opened
            active.add(opened)
            opened.requestMethod = request.method
            opened.connectTimeout = 10_000
            opened.readTimeout = 15_000
            opened.instanceFollowRedirects = false
            opened.useCaches = false
            opened.setRequestProperty("Accept", "application/json")
            request.bearerToken?.let { opened.setRequestProperty("Authorization", "Bearer $it") }
            request.body?.let { body ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 2048)
                opened.doOutput = true
                opened.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                opened.setFixedLengthStreamingMode(bytes.size)
                opened.outputStream.use { it.write(bytes) }
            }
            currentCoroutineContext().ensureActive()
            val status = opened.responseCode
            // Error bodies are never displayed, logged or parsed into an entitlement.
            if (status !in 200..299) return@withContext PaymentResponse(status, "")
            require(opened.contentLengthLong <= MAX_RESPONSE_BYTES)
            val bytes = ByteArrayOutputStream()
            opened.inputStream.use { stream ->
                val buffer = ByteArray(2048)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= MAX_RESPONSE_BYTES)
                    bytes.write(buffer, 0, count)
                }
            }
            currentCoroutineContext().ensureActive()
            PaymentResponse(status, bytes.toString(Charsets.UTF_8.name()))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw PaymentException(PaymentFailure.CONNECTION)
        } finally {
            connection?.let { active.remove(it); it.disconnect() }
        }
    }

    override fun cancelActiveRequests() { active.forEach { it.disconnect() } }
}
