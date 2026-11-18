package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractSchema
import dev.kodakiko.contract.ErrorCode
import kotlinx.serialization.json.JsonElement

data class ApiCall<T>(
    val method: String,
    val path: String,
    val body: JsonElement?,
    val responseSchema: ContractSchema<T>,
    val auth: ApiAuth,
    val idempotent: Boolean,
    val timeoutMs: Long? = null,
)

sealed interface ApiAuth {
    data object Session : ApiAuth

    data object None : ApiAuth

    data class Link(val linkToken: String, val viewToken: String?) : ApiAuth
}

class ApiError(
    val status: Int,
    val code: ErrorCode,
    val details: JsonElement?,
    val requestId: String?,
    val retryAfterSeconds: Double? = null,
) : Exception(HttpText.apiError(code.wire))

class NetworkError : Exception(HttpText.NETWORK_ERROR)
