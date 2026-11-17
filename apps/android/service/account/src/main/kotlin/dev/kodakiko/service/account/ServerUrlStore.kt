package dev.kodakiko.service.account

import dev.kodakiko.contract.ContractJson
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ContractViolation
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.Routes
import dev.kodakiko.js.jsTrim
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class ServerChoiceFailure { INVALID, NOT_HTTPS, UNREACHABLE, NOT_KODAKIKO }

sealed interface ServerChoice {
    data class Ok(val baseUrl: String, val config: PublicConfigDto, val inviteToken: String?) : ServerChoice

    data class Failed(val reason: ServerChoiceFailure) : ServerChoice
}

interface ServerUrlStore {
    suspend fun current(): String?

    suspend fun choose(input: String): ServerChoice

    suspend fun clear()
}

internal object ServerUrls {
    sealed interface Parsed {
        data class Valid(val url: HttpUrl) : Parsed

        data class Refused(val reason: ServerChoiceFailure) : Parsed
    }

    fun parseInput(input: String, allowLocalHttp: Boolean): Parsed {
        val trimmed = jsTrim(input)
        if (trimmed.isEmpty()) return Parsed.Refused(ServerChoiceFailure.INVALID)
        val candidate = if (ServerUrlConstants.SCHEME_PATTERN.containsMatchIn(trimmed)) trimmed else ServerUrlConstants.DEFAULT_SCHEME + trimmed
        val scheme = candidate.substringBefore(ServerUrlConstants.SCHEME_SEPARATOR).lowercase()
        if (scheme != ServerUrlConstants.HTTP_SCHEME && scheme != ServerUrlConstants.HTTPS_SCHEME) {
            return Parsed.Refused(ServerChoiceFailure.INVALID)
        }
        val url = candidate.toHttpUrlOrNull() ?: return Parsed.Refused(ServerChoiceFailure.INVALID)
        if (url.scheme == ServerUrlConstants.HTTP_SCHEME) {
            val allowed = allowLocalHttp && url.host in ServerUrlConstants.LOCAL_HTTP_HOSTS
            return if (allowed) Parsed.Valid(url) else Parsed.Refused(ServerChoiceFailure.NOT_HTTPS)
        }
        return Parsed.Valid(url)
    }

    fun origin(url: HttpUrl): String =
        HttpUrl.Builder().scheme(url.scheme).host(url.host).port(url.port).build().toString().removeSuffix(ServerUrlConstants.ORIGIN_PATH)

    fun inviteToken(url: HttpUrl): String? {
        if (url.encodedPath != ServerUrlConstants.INVITE_PATH) return null
        return url.encodedFragment?.takeIf { it.isNotEmpty() }
    }

    fun host(baseUrl: String): String? {
        val url = baseUrl.toHttpUrlOrNull() ?: return null
        val host = if (url.host.contains(AccountConstants.PORT_SEPARATOR)) AccountConstants.IPV6_OPEN + url.host + AccountConstants.IPV6_CLOSE else url.host
        return if (url.port == HttpUrl.defaultPort(url.scheme)) host else host + AccountConstants.PORT_SEPARATOR + url.port
    }
}

class HttpServerUrlStore(
    okHttpClient: OkHttpClient,
    private val globalStore: GlobalStorePort,
    timeoutMs: Long,
    private val allowLocalHttp: Boolean,
    private val ioDispatcher: CoroutineDispatcher,
) : ServerUrlStore {
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun current(): String? = withContext(ioDispatcher) { globalStore.serverUrl() }

    override suspend fun choose(input: String): ServerChoice {
        val parsed = when (val result = ServerUrls.parseInput(input, allowLocalHttp)) {
            is ServerUrls.Parsed.Refused -> return ServerChoice.Failed(result.reason)
            is ServerUrls.Parsed.Valid -> result.url
        }
        val origin = ServerUrls.origin(parsed)
        return withContext(ioDispatcher) {
            when (val fetched = fetchConfig(origin)) {
                is Fetched.Config -> {
                    globalStore.setServerUrl(origin)
                    ServerChoice.Ok(origin, fetched.config, ServerUrls.inviteToken(parsed))
                }
                is Fetched.Refused -> ServerChoice.Failed(fetched.reason)
            }
        }
    }

    override suspend fun clear() = withContext(ioDispatcher) { globalStore.clearServerUrl() }

    private sealed interface Fetched {
        data class Config(val config: PublicConfigDto) : Fetched

        data class Refused(val reason: ServerChoiceFailure) : Fetched
    }

    private fun fetchConfig(origin: String): Fetched {
        val request = Request.Builder().url(origin + Routes.config()).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string().removePrefix(ServerUrlConstants.BYTE_ORDER_MARK)
                if (!response.isSuccessful) return Fetched.Refused(ServerChoiceFailure.NOT_KODAKIKO)
                Fetched.Config(ContractSchemas.publicConfigSchema.decode(ContractJson.parse(text)))
            }
        } catch (violation: ContractViolation) {
            Fetched.Refused(ServerChoiceFailure.NOT_KODAKIKO)
        } catch (failure: IOException) {
            Fetched.Refused(ServerChoiceFailure.UNREACHABLE)
        } catch (failure: RuntimeException) {
            Fetched.Refused(ServerChoiceFailure.UNREACHABLE)
        }
    }
}
