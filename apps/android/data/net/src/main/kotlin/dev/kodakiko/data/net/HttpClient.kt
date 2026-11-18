package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractJson
import dev.kodakiko.contract.ContractSchema
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ContractViolation
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.js.jsStringToNumber
import dev.kodakiko.js.jsTrim
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

fun backoffDelay(retryIndex: Int, random: Double): Long {
    val ceiling = min(HttpConstants.RETRY_CAP_MS, HttpConstants.RETRY_BASE_MS * HttpConstants.BACKOFF_FACTOR.pow(retryIndex))
    return floor(random * ceiling).toLong()
}

class HttpClient(
    private val baseUrl: () -> String,
    private val sessionToken: () -> String?,
    private val onSessionInvalid: () -> Unit,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val random: () -> Double = { Math.random() },
    okHttpClient: OkHttpClient = OkHttpClient(),
) {
    private val okHttp: OkHttpClient = okHttpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(HttpConstants.NO_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(HttpConstants.NO_TIMEOUT, TimeUnit.MILLISECONDS)
        .writeTimeout(HttpConstants.NO_TIMEOUT, TimeUnit.MILLISECONDS)
        .addNetworkInterceptor(RetryAfterShield)
        .build()

    fun <T> request(call: ApiCall<T>): T {
        val url = (baseUrl() + call.path).toHttpUrlOrNull()
        val headers = buildHeaders(call)
        val body = buildBody(call)
        val client = okHttp.newBuilder().callTimeout(call.timeoutMs ?: HttpConstants.TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        var attempt = 0
        while (true) {
            val raw = url?.let { send(client, Request.Builder().url(it).headers(headers).method(call.method, body).build()) }
            val canRetry = call.idempotent && attempt + 1 < HttpConstants.RETRY_ATTEMPTS
            when {
                raw == null -> {
                    if (!canRetry) throw NetworkError()
                    sleep(backoffDelay(attempt, random()))
                }
                isRetryableStatus(raw.status) && canRetry -> sleep(retryDelay(raw, attempt))
                isSuccess(raw.status) -> return success(raw, call.responseSchema)
                else -> throw failure(raw)
            }
            attempt += 1
        }
    }

    private fun buildHeaders(call: ApiCall<*>): Headers {
        val headers = Headers.Builder()
        if (call.body != null) headers.set(HttpKeys.CONTENT_TYPE, HttpKeys.JSON_CONTENT_TYPE)
        when (val auth = call.auth) {
            ApiAuth.Session -> sessionToken()?.let { headers.set(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + it) }
            ApiAuth.None -> Unit
            is ApiAuth.Link -> {
                headers.set(HttpKeys.LINK_TOKEN, auth.linkToken)
                auth.viewToken?.let { headers.set(HttpKeys.VIEW_TOKEN, it) }
            }
        }
        return headers.build()
    }

    private fun buildBody(call: ApiCall<*>): RequestBody? = when {
        call.body != null -> ContractJson.stringify(call.body).encodeToByteArray().toRequestBody(null)
        call.method in HttpKeys.METHODS_WITH_BODY -> ByteArray(0).toRequestBody(null)
        else -> null
    }

    private fun send(client: OkHttpClient, request: Request): RawResponse? = try {
        client.newCall(request).execute().use { response ->
            RawResponse(response.code, response.headers, response.body.bytes().decodeToString().removePrefix(HttpKeys.BYTE_ORDER_MARK))
        }
    } catch (error: IOException) {
        null
    }

    private fun retryDelay(raw: RawResponse, retryIndex: Int): Long {
        if (raw.status == HttpConstants.STATUS_TOO_MANY_REQUESTS) {
            val header = raw.retryAfter()
            val seconds = header?.let(::jsStringToNumber)
            if (seconds != null && seconds.isFinite() && seconds >= 0) {
                return min(seconds * HttpConstants.MS_PER_SECOND, HttpConstants.RETRY_AFTER_MAX_MS.toDouble()).toLong()
            }
        }
        return backoffDelay(retryIndex, random())
    }

    private fun retryAfterSeconds(raw: RawResponse): Double? {
        val header = raw.retryAfter()
        val seconds = if (header == null || jsTrim(header).isEmpty()) Double.NaN else jsStringToNumber(header)
        return seconds.takeIf { it.isFinite() && it >= 0 }
    }

    private fun failure(raw: RawResponse): ApiError {
        val parsed = (parseJson(raw.text) as? ParsedBody.Value)?.let { decodeOrNull(ContractSchemas.errorResponseSchema, it.json) }
            ?: return ApiError(raw.status, ErrorCode.INTERNAL, null, raw.header(HttpKeys.REQUEST_ID), retryAfterSeconds(raw))
        val error = parsed.value.error
        if (raw.status == HttpConstants.STATUS_UNAUTHORIZED && error.code in SESSION_INVALID_CODES) onSessionInvalid()
        return ApiError(raw.status, error.code, error.details.takeUnless { it is JsonNull }, error.requestId, retryAfterSeconds(raw))
    }

    private fun <T> success(raw: RawResponse, schema: ContractSchema<T>): T {
        when (val parsed = parseJson(raw.text)) {
            ParsedBody.Undefined -> if (schema is EmptyBodySchema<T>) return schema.decodeEmpty()
            is ParsedBody.Value -> decodeOrNull(schema, parsed.json)?.let { return it.value }
            ParsedBody.Invalid -> Unit
        }
        throw ApiError(raw.status, ErrorCode.INTERNAL, null, raw.header(HttpKeys.REQUEST_ID))
    }

    private companion object {
        val SESSION_INVALID_CODES = setOf(ErrorCode.UNAUTHENTICATED, ErrorCode.SESSION_EXPIRED)
    }
}

private class Decoded<T>(val value: T)

private fun <T> decodeOrNull(schema: ContractSchema<T>, json: JsonElement): Decoded<T>? = try {
    Decoded(schema.decode(json))
} catch (violation: ContractViolation) {
    null
}

private fun isSuccess(status: Int): Boolean = status >= HttpConstants.STATUS_OK && status < HttpConstants.STATUS_MULTIPLE_CHOICES

private fun isRetryableStatus(status: Int): Boolean =
    status == HttpConstants.STATUS_TOO_MANY_REQUESTS || status >= HttpConstants.STATUS_SERVER_ERROR_MIN

private sealed interface ParsedBody {
    data object Undefined : ParsedBody

    data object Invalid : ParsedBody

    data class Value(val json: JsonElement) : ParsedBody
}

private fun parseJson(text: String): ParsedBody {
    if (text.isEmpty()) return ParsedBody.Undefined
    return try {
        ParsedBody.Value(ContractJson.parse(text))
    } catch (violation: ContractViolation) {
        ParsedBody.Invalid
    }
}

private class RawResponse(val status: Int, val headers: Headers, val text: String) {
    fun header(name: String): String? = headers.values(name).takeIf { it.isNotEmpty() }?.joinToString(HttpKeys.HEADER_VALUE_SEPARATOR)

    fun retryAfter(): String? = header(HttpKeys.SHIELDED_RETRY_AFTER) ?: header(HttpKeys.RETRY_AFTER)
}

private object RetryAfterShield : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val values = response.headers(HttpKeys.RETRY_AFTER)
        if (response.code != HttpConstants.STATUS_SERVICE_UNAVAILABLE || values.isEmpty()) return response
        val shielded = response.newBuilder().removeHeader(HttpKeys.RETRY_AFTER)
        values.forEach { shielded.addHeader(HttpKeys.SHIELDED_RETRY_AFTER, it) }
        return shielded.build()
    }
}
