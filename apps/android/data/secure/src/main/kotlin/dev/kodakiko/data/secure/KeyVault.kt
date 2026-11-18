package dev.kodakiko.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.wipe
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher

enum class DeviceLock { NONE, SECURE, BIOMETRIC }

enum class DeviceGateReason { UNLOCK, STORE_MASTER_KEY, LEGACY_UNLOCK }

interface DeviceGate {
    fun authenticate(reason: DeviceGateReason): Boolean

    fun authenticateCipher(reason: DeviceGateReason, cipher: Cipher): Cipher? = null
}

enum class KeyVaultMode(val wire: String) {
    NONE("none"),
    DEVICE("device"),
    BIOMETRIC("biometric"),
    ;

    companion object {
        fun fromWire(wire: String?): KeyVaultMode? = entries.firstOrNull { it.wire == wire }
    }
}

data class KeyVaultCapabilities(val persistent: Boolean, val mode: KeyVaultMode, val available: Boolean)

class KeyVault(context: Context, private val lock: () -> DeviceLock, private val gate: DeviceGate) {
    private val preferences: SharedPreferences = context.getSharedPreferences(SecureConstants.VAULT_FILE, Context.MODE_PRIVATE)

    fun capabilities(accountHash: String): KeyVaultCapabilities {
        val current = lock()
        val stored = storedItem(accountHash)
        val available = current != DeviceLock.NONE
        val mode = when {
            !available -> KeyVaultMode.NONE
            stored != null -> KeyVaultMode.fromWire(stored.mode) ?: KeyVaultMode.NONE
            else -> modeFor(current)
        }
        return KeyVaultCapabilities(persistent = available && stored != null, mode = mode, available = available)
    }

    fun store(accountHash: String, masterKey: ByteArray): Boolean {
        val name = name(accountHash)
        val current = lock()
        if (masterKey.size != KEY_BYTES || current == DeviceLock.NONE) return false
        val mode = modeFor(current)
        val cipher = encryptCipher(mode) ?: return false
        val ready = if (mode == KeyVaultMode.BIOMETRIC) gate.authenticateCipher(DeviceGateReason.STORE_MASTER_KEY, cipher) else cipher
        ready ?: return false
        val sealed = keystoreOrNull {
            ready.updateAAD(additionalData(SecureConstants.VAULT_FILE, name + SecureConstants.AAD_SEPARATOR + mode.wire))
            SealedItem(ready.iv, ready.doFinal(masterKey), mode.wire)
        } ?: return false
        preferences.edit().putString(name, sealed.encode()).commitOrThrow()
        return true
    }

    fun read(accountHash: String): ByteArray? {
        val name = name(accountHash)
        if (lock() == DeviceLock.NONE) return null
        val item = storedItem(accountHash) ?: return null
        val mode = KeyVaultMode.fromWire(item.mode)
        if (mode == null || mode == KeyVaultMode.NONE) return null
        val key = keystoreOrNull { Keystore.existingKey(aliasFor(mode)) } ?: return null
        val cipher = try {
            Keystore.decryptCipher(key, item.iv)
        } catch (failure: KeyPermanentlyInvalidatedException) {
            forget(accountHash)
            return null
        } catch (failure: GeneralSecurityException) {
            return null
        } catch (failure: ProviderException) {
            return null
        }
        val ready = when (mode) {
            KeyVaultMode.BIOMETRIC -> gate.authenticateCipher(DeviceGateReason.UNLOCK, cipher)
            else -> cipher.takeIf { gate.authenticate(DeviceGateReason.UNLOCK) }
        } ?: return null
        val masterKey = keystoreOrNull {
            ready.updateAAD(additionalData(SecureConstants.VAULT_FILE, name + SecureConstants.AAD_SEPARATOR + mode.wire))
            ready.doFinal(item.ciphertext)
        } ?: return null
        if (masterKey.size != KEY_BYTES) {
            wipe(masterKey)
            return null
        }
        return masterKey
    }

    fun forget(accountHash: String) {
        preferences.edit().remove(name(accountHash)).commitOrThrow()
    }

    private fun encryptCipher(mode: KeyVaultMode): Cipher? {
        val alias = aliasFor(mode)
        return try {
            Keystore.encryptCipher(Keystore.key(alias, protectionFor(mode)))
        } catch (failure: KeyPermanentlyInvalidatedException) {
            Keystore.delete(alias)
            keystoreOrNull { Keystore.encryptCipher(Keystore.key(alias, protectionFor(mode))) }
        } catch (failure: GeneralSecurityException) {
            null
        } catch (failure: ProviderException) {
            null
        }
    }

    private fun storedItem(accountHash: String): SealedItem? = preferences.getString(name(accountHash), null)?.let(SealedItem::decode)

    private fun name(accountHash: String): String = SecureConstants.MASTER_KEY_PREFIX + requireAccountHash(accountHash)

    private fun modeFor(lock: DeviceLock): KeyVaultMode =
        when (lock) {
            DeviceLock.NONE -> KeyVaultMode.NONE
            DeviceLock.SECURE -> KeyVaultMode.DEVICE
            DeviceLock.BIOMETRIC -> KeyVaultMode.BIOMETRIC
        }

    private fun aliasFor(mode: KeyVaultMode): String =
        if (mode == KeyVaultMode.BIOMETRIC) SecureConstants.VAULT_BIOMETRIC_ALIAS else SecureConstants.VAULT_DEVICE_ALIAS

    private fun protectionFor(mode: KeyVaultMode): KeyProtection = if (mode == KeyVaultMode.BIOMETRIC) KeyProtection.BIOMETRIC else KeyProtection.NONE
}
