package dev.kodakiko.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ExpoSecureStoreWriter(private val context: Context) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val unauthenticatedAlias = "AES/GCM/NoPadding:key_v1:keystoreUnauthenticated"
    private val authenticatedAlias = "AES/GCM/NoPadding:key_v1:keystoreAuthenticated"
    private val prefs get() = context.getSharedPreferences("SecureStore", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        (keyStore.getEntry(unauthenticatedAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(unauthenticatedAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val gcm = cipher.parameters.getParameterSpec(GCMParameterSpec::class.java)
        val item = JSONObject()
            .put("ct", Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(gcm.iv, Base64.NO_WRAP))
            .put("tlen", gcm.tLen)
            .put("scheme", "aes")
            .put("usesKeystoreSuffix", true)
            .put("keystoreAlias", "key_v1")
            .put("requireAuthentication", false)
        check(prefs.edit().putString("key_v1-$name", item.toString()).commit())
    }

    fun writeAuthenticatedPlaceholder(name: String) {
        val item = JSONObject()
            .put("ct", Base64.encodeToString(ByteArray(48), Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(ByteArray(12), Base64.NO_WRAP))
            .put("tlen", 128)
            .put("scheme", "aes")
            .put("usesKeystoreSuffix", true)
            .put("keystoreAlias", "key_v1")
            .put("requireAuthentication", true)
        check(prefs.edit().putString("key_v1-$name", item.toString()).commit())
    }

    fun writeRaw(name: String, raw: String) {
        check(prefs.edit().putString("key_v1-$name", raw).commit())
    }

    fun reset() {
        check(prefs.edit().clear().commit())
        for (alias in listOf(unauthenticatedAlias, authenticatedAlias)) if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    fun preferenceNames(): Set<String> = prefs.all.keys

    fun aliasExists(): Boolean = keyStore.containsAlias(unauthenticatedAlias)
}
