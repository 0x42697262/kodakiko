package dev.kodakiko.service.account

import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.data.secure.ActiveAccount
import kotlinx.serialization.json.JsonElement

interface SessionStorePort {
    fun save(accountKey: String, token: String)

    fun load(accountKey: String): String?

    fun clear(accountKey: String)
}

interface KeyVaultPort {
    fun storeMasterKey(accountKey: String, masterKey: ByteArray)

    fun loadMasterKey(accountKey: String): ByteArray?

    fun forget(accountKey: String)
}

interface GlobalStorePort {
    fun serverUrl(): String?

    fun setServerUrl(url: String)

    fun clearServerUrl()

    fun activeAccount(): ActiveAccount?

    fun setActiveAccount(account: ActiveAccount)

    fun clearActiveAccount()

    fun serverConfigJson(): String?

    fun setServerConfigJson(json: String)

    fun clearServerConfigJson()
}

interface RowStorePort {
    fun open(accountKey: String)

    fun close()

    fun destroy(accountKey: String)

    fun <T> transaction(work: (RowStoreTx) -> T): T

    fun kvGet(key: String): ByteArray?

    fun kvGetJson(key: String): JsonElement?

    fun kvSet(key: String, value: ByteArray)

    fun kvSetJson(key: String, value: JsonElement)

    fun kvDelete(key: String)
}

interface FileStorePort {
    fun open(accountKey: String)

    fun destroy(accountKey: String)
}

interface LifecyclePort {
    suspend fun afterInteractions()

    fun subscribeActive(listener: (Boolean) -> Unit): () -> Unit
}
