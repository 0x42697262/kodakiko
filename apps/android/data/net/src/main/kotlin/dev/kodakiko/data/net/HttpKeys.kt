package dev.kodakiko.data.net

object HttpKeys {
    const val GET = "GET"
    const val POST = "POST"
    const val PATCH = "PATCH"
    const val DELETE = "DELETE"
    const val AUTHORIZATION = "Authorization"
    const val CONTENT_TYPE = "Content-Type"
    const val LINK_TOKEN = "X-Kodakiko-Link-Token"
    const val VIEW_TOKEN = "X-Kodakiko-View-Token"
    const val REQUEST_ID = "X-Request-Id"
    const val RETRY_AFTER = "Retry-After"
    const val SHIELDED_RETRY_AFTER = "X-Kodakiko-Shielded-Retry-After"
    const val BEARER_PREFIX = "Bearer "
    const val JSON_CONTENT_TYPE = "application/json"
    const val HEADER_VALUE_SEPARATOR = ", "
    const val QUERY_START = "?"
    const val QUERY_SEPARATOR = "&"
    const val CURSOR_PARAMETER = "cursor="
    const val SINCE_PARAMETER = "since="
    const val LIMIT_PARAMETER = "limit="
    const val BYTE_ORDER_MARK = "\uFEFF"
    val METHODS_WITH_BODY: Set<String> = setOf(POST, PATCH)
}

internal object HttpText {
    const val NETWORK_ERROR = "The server could not be reached."

    fun apiError(code: String): String = "The server answered with $code."
}
