package dev.kodakiko.account

import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.DecryptionError
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wipe

class KeyBundleBytes(
    val salt: ByteArray,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val masterKeyByKek: ByteArray,
    val recoveryKeyByMaster: ByteArray,
    val publicKey: ByteArray,
    val privateKeyByMaster: ByteArray,
)

class AccountKeys(val masterKey: ByteArray, val privateKey: ByteArray, val publicKey: ByteArray)

fun openKeyBundle(sodium: Sodium, kek: ByteArray, userId: String, bundle: KeyBundleBytes): AccountKeys {
    val masterKey = try {
        unwrapKey(sodium, kek, bundle.masterKeyByKek, AdContext.MASTER_KEY_BY_KEK, userId)
    } catch (error: DecryptionError) {
        throw WrongPasswordError()
    }
    try {
        val privateKey = unwrapKey(sodium, masterKey, bundle.privateKeyByMaster, AdContext.PRIVATE_KEY_BY_MASTER, userId)
        return AccountKeys(masterKey, privateKey, bundle.publicKey)
    } catch (error: Throwable) {
        wipe(masterKey)
        throw error
    }
}
