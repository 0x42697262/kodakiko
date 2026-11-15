package dev.kodakiko.account

import dev.kodakiko.crypto.Sodium

fun deriveForSignIn(sodium: Sodium, password: String, params: KdfParams): LoginMaterial {
    if (!meetsKdfFloor(params.opsLimit, params.memLimitBytes) || params.salt.size != SALT_BYTES) {
        throw HostileServerParamsError()
    }
    return deriveLoginMaterial(sodium, password, params)
}
