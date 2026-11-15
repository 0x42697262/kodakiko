package dev.kodakiko.account

class KdfParams(val salt: ByteArray, val opsLimit: Long, val memLimitBytes: Long)

fun startKdfParams(salt: ByteArray): KdfParams = KdfParams(salt, ARGON2ID_START_OPS, ARGON2ID_START_MEM_BYTES)

fun nextKdfParamsAfterFailure(params: KdfParams): KdfParams? {
    val memLimitBytes = params.memLimitBytes / ADAPTIVE_FACTOR
    if (memLimitBytes < ARGON2ID_MIN_MEM_BYTES) return null
    return KdfParams(params.salt, params.opsLimit * ADAPTIVE_FACTOR, memLimitBytes)
}

fun meetsKdfFloor(opsLimit: Long, memLimitBytes: Long): Boolean =
    memLimitBytes >= ARGON2ID_MIN_MEM_BYTES &&
        memLimitBytes.toDouble() * opsLimit.toDouble() >= ARGON2ID_MIN_WORK.toDouble()
