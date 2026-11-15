package dev.kodakiko.service.account

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.files.accountHash
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.GlobalStore
import dev.kodakiko.data.secure.KeyVault
import dev.kodakiko.data.secure.SessionStore
import dev.kodakiko.platform.device.ForegroundTracker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SecureSessionStore(private val store: SessionStore, private val sodium: Sodium) : SessionStorePort {
    override fun save(accountKey: String, token: String) {
        store.save(accountHash(sodium, accountKey), token)
    }

    override fun load(accountKey: String): String? = store.load(accountHash(sodium, accountKey))

    override fun clear(accountKey: String) {
        store.clear(accountHash(sodium, accountKey))
    }
}

class SecureKeyVault(private val vault: KeyVault, private val sodium: Sodium) : KeyVaultPort {
    override fun storeMasterKey(accountKey: String, masterKey: ByteArray) {
        vault.store(accountHash(sodium, accountKey), masterKey)
    }

    override fun loadMasterKey(accountKey: String): ByteArray? = vault.read(accountHash(sodium, accountKey))

    override fun forget(accountKey: String) {
        vault.forget(accountHash(sodium, accountKey))
    }
}

class SecureGlobalStore(private val store: GlobalStore) : GlobalStorePort {
    override fun serverUrl(): String? = store.serverUrl()

    override fun setServerUrl(url: String) {
        store.setServerUrl(url)
    }

    override fun clearServerUrl() {
        store.clearServerUrl()
    }

    override fun activeAccount(): ActiveAccount? = store.activeAccount()

    override fun setActiveAccount(account: ActiveAccount) {
        store.setActiveAccount(account)
    }

    override fun clearActiveAccount() {
        store.clearActiveAccount()
    }

    override fun serverConfigJson(): String? = store.serverConfigJson()

    override fun setServerConfigJson(json: String) {
        store.setServerConfigJson(json)
    }

    override fun clearServerConfigJson() {
        store.clearServerConfigJson()
    }
}

class ForegroundLifecycle(private val tracker: ForegroundTracker, looper: Looper = Looper.getMainLooper()) : LifecyclePort {
    private val handler = Handler(looper)

    override suspend fun afterInteractions() =
        suspendCancellableCoroutine { continuation ->
            val idle = MessageQueue.IdleHandler {
                if (continuation.isActive) continuation.resume(Unit)
                false
            }
            handler.post { Looper.myQueue().addIdleHandler(idle) }
            continuation.invokeOnCancellation { handler.post { Looper.myQueue().removeIdleHandler(idle) } }
        }

    override fun subscribeActive(listener: (Boolean) -> Unit): () -> Unit = tracker.subscribe(listener)
}
