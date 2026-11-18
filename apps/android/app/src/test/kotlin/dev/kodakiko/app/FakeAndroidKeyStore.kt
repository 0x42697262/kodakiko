package dev.kodakiko.app

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object FakeAndroidKeyStore {
    private const val NAME = "AndroidKeyStore"
    private const val AES = "AES"
    private const val DEFAULT_KEY_BITS = 256
    private const val BITS_PER_BYTE = 8
    private const val PROVIDER_VERSION = 1.0

    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        if (Security.getProvider(NAME) == null) Security.addProvider(FakeProvider())
    }

    fun uninstall() {
        Security.removeProvider(NAME)
        keys.clear()
    }

    private class FakeProvider : Provider(NAME, PROVIDER_VERSION, "Software stand-in for the Android Keystore in Robolectric tests") {
        init {
            putService(
                object : Service(this, "KeyStore", NAME, FakeKeyStoreSpi::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = FakeKeyStoreSpi()
                },
            )
            putService(
                object : Service(this, "KeyGenerator", AES, FakeKeyGeneratorSpi::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = FakeKeyGeneratorSpi()
                },
            )
        }
    }

    private class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
        private var alias: String? = null
        private var bits = DEFAULT_KEY_BITS
        private var random = SecureRandom()

        override fun engineInit(random: SecureRandom) {
            this.random = random
        }

        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            val spec = params as KeyGenParameterSpec
            alias = spec.keystoreAlias
            if (spec.keySize > 0) bits = spec.keySize
            if (random != null) this.random = random
        }

        override fun engineInit(keysize: Int, random: SecureRandom) {
            bits = keysize
            this.random = random
        }

        override fun engineGenerateKey(): SecretKey {
            val key = SecretKeySpec(ByteArray(bits / BITS_PER_BYTE).also(random::nextBytes), AES)
            keys[requireNotNull(alias)] = key
            return key
        }
    }

    private class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
            keys[alias]?.let { KeyStore.SecretKeyEntry(it) }

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date(0) else null

        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }

        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
            keys[alias] = SecretKeySpec(key, AES)
        }

        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit

        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)

        override fun engineSize(): Int = keys.size

        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }
}
