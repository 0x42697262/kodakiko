package dev.kodakiko.data.migration

import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.DeviceGate
import dev.kodakiko.data.secure.DeviceGateReason
import dev.kodakiko.data.secure.GlobalStore
import dev.kodakiko.data.secure.KeyVault
import dev.kodakiko.data.secure.LegacyRead
import dev.kodakiko.data.secure.LegacySecureStore
import dev.kodakiko.data.secure.SessionStore

class LegacySecureStoreSecrets(private val store: LegacySecureStore, private val gate: DeviceGate? = null) : LegacySecrets {
    override fun read(name: String): LegacyRead {
        val read = store.read(name)
        if (read != LegacyRead.NeedsAuthentication || gate == null) return read
        return store.readAuthenticated(name) { cipher -> gate.authenticateCipher(DeviceGateReason.LEGACY_UNLOCK, cipher) }
    }

    override fun forgetAll() {
        store.forgetAll()
    }
}

class SecureStoresTarget(private val global: GlobalStore, private val sessions: SessionStore, private val vault: KeyVault) : AdoptionTarget {
    override fun saveServerUrl(url: String) {
        global.setServerUrl(url)
    }

    override fun saveActiveAccount(account: ActiveAccount) {
        global.setActiveAccount(account)
    }

    override fun saveServerConfigJson(json: String) {
        global.setServerConfigJson(json)
    }

    override fun saveSession(accountHash: String, token: String) {
        sessions.save(accountHash, token)
    }

    override fun saveMasterKey(accountHash: String, masterKey: ByteArray): Boolean = vault.store(accountHash, masterKey)
}
