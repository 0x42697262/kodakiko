package dev.kodakiko.account

import dev.kodakiko.crypto.Argon2idInput
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.KdfContext
import dev.kodakiko.crypto.KdfSubkeyIds
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.passwordToBytes
import dev.kodakiko.crypto.wipe

class LoginMaterial(val kek: ByteArray, val loginKey: ByteArray)

class AdaptiveDerivation(val params: KdfParams, val material: LoginMaterial)

private fun stretchPassword(sodium: Sodium, password: String, params: KdfParams): ByteArray {
    val passwordBytes = passwordToBytes(password)
    try {
        return sodium.argon2id(Argon2idInput(passwordBytes, params.salt, params.opsLimit, params.memLimitBytes, KEK_BYTES))
    } finally {
        wipe(passwordBytes)
    }
}

private fun materialFromKek(sodium: Sodium, kek: ByteArray): LoginMaterial =
    LoginMaterial(kek, sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.LOGIN, KdfContext.LOGIN.value, kek))

fun deriveLoginMaterial(sodium: Sodium, password: String, params: KdfParams): LoginMaterial =
    materialFromKek(sodium, stretchPassword(sodium, password, params))

fun deriveWithAdaptiveParams(
    sodium: Sodium,
    password: String,
    salt: ByteArray,
    startParams: (ByteArray) -> KdfParams = ::startKdfParams,
): AdaptiveDerivation {
    var params: KdfParams? = startParams(salt)
    while (params != null) {
        val kek = try {
            stretchPassword(sodium, password, params)
        } catch (error: Exception) {
            null
        }
        if (kek != null) return AdaptiveDerivation(params, materialFromKek(sodium, kek))
        params = nextKdfParamsAfterFailure(params)
    }
    throw KdfUnavailableError()
}

fun recoveryLoginKey(sodium: Sodium, recoveryKey: ByteArray): ByteArray =
    sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.RECOVERY_LOGIN, KdfContext.RECOVERY.value, recoveryKey)
