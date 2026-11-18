package dev.kodakiko.crypto

fun interface RandomSource {
    fun next(length: Int): ByteArray
}

fun counterBytes(seed: Int, length: Int): ByteArray = ByteArray(length) { index -> ((seed + index) and BYTE_MASK).toByte() }

fun createCounterRandom(seed: Int): RandomSource {
    var drawn = 0
    return RandomSource { length ->
        val bytes = counterBytes(seed + drawn, length)
        drawn += length
        bytes
    }
}

fun Sodium.withRandomSource(random: RandomSource): Sodium = RandomSourceSodium(this, random)

private class RandomSourceSodium(base: Sodium, private val random: RandomSource) : Sodium by base {
    override fun randomBytes(length: Int): ByteArray = random.next(length)
}
