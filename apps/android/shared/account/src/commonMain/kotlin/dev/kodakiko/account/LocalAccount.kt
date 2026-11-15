package dev.kodakiko.account

import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.DecryptionError
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wipe

class StoredLocalAccount(
    val userId: String,
    val kdfParams: KdfParams,
    val masterKeyByKek: ByteArray,
    val masterKeyByRecovery: ByteArray,
    val recoveryKeyByMaster: ByteArray,
    val publicKey: ByteArray,
    val privateKeyByMaster: ByteArray,
    val homeCollection: HomeCollectionBytes,
)

fun rebuildSignUpRequest(sodium: Sodium, password: String, username: String, stored: StoredLocalAccount): SignUpRequestBytes {
    val login = deriveLoginMaterial(sodium, password, stored.kdfParams)
    val masterKey = try {
        unwrapKey(sodium, login.kek, stored.masterKeyByKek, AdContext.MASTER_KEY_BY_KEK, stored.userId)
    } catch (error: Throwable) {
        wipe(login.loginKey)
        if (error is DecryptionError) throw WrongPasswordError(error.message)
        throw error
    } finally {
        wipe(login.kek)
    }
    var recoveryKey: ByteArray? = null
    try {
        val unwrappedRecoveryKey = unwrapKey(sodium, masterKey, stored.recoveryKeyByMaster, AdContext.RECOVERY_KEY_BY_MASTER, stored.userId)
        recoveryKey = unwrappedRecoveryKey
        return SignUpRequestBytes(
            userId = stored.userId,
            username = username,
            salt = stored.kdfParams.salt,
            opsLimit = stored.kdfParams.opsLimit,
            memLimitBytes = stored.kdfParams.memLimitBytes,
            loginKey = login.loginKey,
            recoveryLoginKey = recoveryLoginKey(sodium, unwrappedRecoveryKey),
            publicKey = stored.publicKey,
            masterKeyByKek = stored.masterKeyByKek,
            masterKeyByRecovery = stored.masterKeyByRecovery,
            recoveryKeyByMaster = stored.recoveryKeyByMaster,
            privateKeyByMaster = stored.privateKeyByMaster,
            homeCollection = stored.homeCollection,
        )
    } finally {
        wipe(masterKey)
        recoveryKey?.let(::wipe)
    }
}
