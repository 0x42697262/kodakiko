package dev.kodakiko.data.secure

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class KeyProtection { NONE, BIOMETRIC }

internal object Keystore {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(SecureConstants.KEYSTORE_PROVIDER).apply { load(null) } }

    fun existingKey(alias: String): SecretKey? =
        synchronized(this) { (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey }

    fun key(alias: String, protection: KeyProtection): SecretKey =
        synchronized(this) { existingKey(alias) ?: generate(alias, protection) }

    fun contains(alias: String): Boolean = synchronized(this) { keyStore.containsAlias(alias) }

    fun delete(alias: String) {
        synchronized(this) { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
    }

    fun encryptCipher(key: SecretKey): Cipher = Cipher.getInstance(SecureConstants.AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) }

    fun decryptCipher(key: SecretKey, iv: ByteArray, tagBits: Int = SecureConstants.GCM_TAG_BITS): Cipher =
        Cipher.getInstance(SecureConstants.AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(tagBits, iv)) }

    private fun generate(alias: String, protection: KeyProtection): SecretKey {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(SecureConstants.AES_KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        if (protection == KeyProtection.BIOMETRIC) requireBiometric(builder)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, SecureConstants.KEYSTORE_PROVIDER).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun requireBiometric(builder: KeyGenParameterSpec.Builder) {
        builder.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(SecureConstants.BIOMETRIC_AUTH_TIMEOUT_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
    }
}
