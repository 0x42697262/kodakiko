package dev.kodakiko.account

import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.DecryptionError
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wipe
import dev.kodakiko.crypto.wrapKey

class PasswordChangeInput(
    val userId: String,
    val currentPassword: String,
    val newPassword: String,
    val currentParams: KdfParams,
    val cachedMasterKeyByKek: ByteArray,
)

class PasswordChangeBytes(
    val currentLoginKey: ByteArray,
    val salt: ByteArray,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val loginKey: ByteArray,
    val masterKeyByKek: ByteArray,
)

fun createPasswordChangeMaterial(
    sodium: Sodium,
    input: PasswordChangeInput,
    startParams: (ByteArray) -> KdfParams = ::startKdfParams,
): PasswordChangeBytes {
    val current = deriveLoginMaterial(sodium, input.currentPassword, input.currentParams)
    val masterKey = try {
        unwrapKey(sodium, current.kek, input.cachedMasterKeyByKek, AdContext.MASTER_KEY_BY_KEK, input.userId)
    } catch (error: DecryptionError) {
        throw WrongPasswordError()
    } finally {
        wipe(current.kek)
    }
    try {
        val salt = sodium.randomBytes(SALT_BYTES)
        val derived = deriveWithAdaptiveParams(sodium, input.newPassword, salt, startParams)
        val masterKeyByKek = wrapKey(sodium, derived.material.kek, masterKey, AdContext.MASTER_KEY_BY_KEK, input.userId)
        wipe(derived.material.kek)
        return PasswordChangeBytes(
            currentLoginKey = current.loginKey,
            salt = salt,
            opsLimit = derived.params.opsLimit,
            memLimitBytes = derived.params.memLimitBytes,
            loginKey = derived.material.loginKey,
            masterKeyByKek = masterKeyByKek,
        )
    } finally {
        wipe(masterKey)
    }
}
