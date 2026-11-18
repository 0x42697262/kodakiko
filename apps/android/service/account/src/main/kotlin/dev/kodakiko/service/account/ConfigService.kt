package dev.kodakiko.service.account

import dev.kodakiko.contract.ContractJson
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ContractViolation
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.data.net.ConfigCalls
import dev.kodakiko.data.net.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

interface ConfigService {
    fun current(): PublicConfigDto?

    suspend fun refresh(): PublicConfigDto?

    fun accept(config: PublicConfigDto)

    fun subscribe(listener: (PublicConfigDto) -> Unit): () -> Unit
}

fun storeServerConfig(globalStore: GlobalStorePort, config: PublicConfigDto) {
    globalStore.setServerConfigJson(ContractJson.stringify(ContractSchemas.publicConfigSchema.encode(config)))
}

private fun loadStoredConfig(globalStore: GlobalStorePort): PublicConfigDto? {
    val stored = globalStore.serverConfigJson() ?: return null
    return try {
        ContractSchemas.publicConfigSchema.decode(ContractJson.parse(stored))
    } catch (violation: ContractViolation) {
        null
    }
}

class DefaultConfigService(
    private val http: HttpClient,
    private val globalStore: GlobalStorePort,
    lifecycle: LifecyclePort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : ConfigService {
    @Volatile
    private var current: PublicConfigDto? = loadStoredConfig(globalStore)

    private val listeners = CopyOnWriteArraySet<(PublicConfigDto) -> Unit>()

    init {
        refreshInBackground()
        lifecycle.subscribeActive { active -> if (active) refreshInBackground() }
    }

    override fun current(): PublicConfigDto? = current

    override fun accept(config: PublicConfigDto) {
        current = config
        storeServerConfig(globalStore, config)
        listeners.forEach { it(config) }
    }

    override suspend fun refresh(): PublicConfigDto? {
        try {
            withContext(ioDispatcher) { accept(http.request(ConfigCalls.get())) }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
        }
        return current
    }

    override fun subscribe(listener: (PublicConfigDto) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    private fun refreshInBackground() {
        scope.launch { refresh() }
    }
}
