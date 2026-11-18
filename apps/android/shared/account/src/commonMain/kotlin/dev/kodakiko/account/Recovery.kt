package dev.kodakiko.account

import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.DecryptionError
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.decodeRecoveryWords
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wipe
import dev.kodakiko.crypto.wrapKey

class OpenedRecovery(val masterKey: ByteArray, val recoveryKey: ByteArray)

class RecoveryCompletionInput(val userId: String, val newPassword: String, val masterKey: ByteArray, val recoveryKey: ByteArray)

class RecoveryCompleteBytes(
    val recoveryLoginKey: ByteArray,
    val salt: ByteArray,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val loginKey: ByteArray,
    val masterKeyByKek: ByteArray,
)

fun openRecovery(sodium: Sodium, words: List<String>, userId: String, masterKeyByRecovery: ByteArray): OpenedRecovery {
    val recoveryKey = decodeRecoveryWords(sodium, words)
    try {
        val masterKey = unwrapKey(sodium, recoveryKey, masterKeyByRecovery, AdContext.MASTER_KEY_BY_RECOVERY, userId)
        return OpenedRecovery(masterKey, recoveryKey)
    } catch (error: Throwable) {
        wipe(recoveryKey)
        if (error is DecryptionError) throw WrongRecoveryWordsError()
        throw error
    }
}

fun createRecoveryCompletion(
    sodium: Sodium,
    input: RecoveryCompletionInput,
    startParams: (ByteArray) -> KdfParams = ::startKdfParams,
): RecoveryCompleteBytes {
    val salt = sodium.randomBytes(SALT_BYTES)
    val derived = deriveWithAdaptiveParams(sodium, input.newPassword, salt, startParams)
    val masterKeyByKek = wrapKey(sodium, derived.material.kek, input.masterKey, AdContext.MASTER_KEY_BY_KEK, input.userId)
    wipe(derived.material.kek)
    return RecoveryCompleteBytes(
        recoveryLoginKey = recoveryLoginKey(sodium, input.recoveryKey),
        salt = salt,
        opsLimit = derived.params.opsLimit,
        memLimitBytes = derived.params.memLimitBytes,
        loginKey = derived.material.loginKey,
        masterKeyByKek = masterKeyByKek,
    )
}
