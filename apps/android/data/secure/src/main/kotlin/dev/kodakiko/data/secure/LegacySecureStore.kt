package dev.kodakiko.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher

sealed interface LegacyRead {
    data class Value(val text: String) : LegacyRead {
        override fun toString(): String = SecureText.REDACTED_VALUE
    }

    class Bytes(val bytes: ByteArray) : LegacyRead {
        override fun toString(): String = SecureText.REDACTED_BYTES
    }

    data object Missing : LegacyRead

    data object NeedsAuthentication : LegacyRead

    data class Unreadable(val reason: String) : LegacyRead
}

class LegacySecureStore(private val context: Context) {
    private val preferences: SharedPreferences get() = context.getSharedPreferences(LegacyConstants.PREFERENCES_FILE, Context.MODE_PRIVATE)

    fun read(name: String): LegacyRead = read(name, asBytes = false)

    fun readBytes(name: String): LegacyRead = read(name, asBytes = true)

    fun readAuthenticated(name: String, authenticate: (Cipher) -> Cipher?): LegacyRead = readAuthenticated(name, authenticate, asBytes = false)

    fun readAuthenticatedBytes(name: String, authenticate: (Cipher) -> Cipher?): LegacyRead = readAuthenticated(name, authenticate, asBytes = true)

    private fun read(name: String, asBytes: Boolean): LegacyRead {
        val item = when (val parsed = item(name)) {
            is ParsedItem.Done -> return parsed.read
            is ParsedItem.Found -> parsed
        }
        if (item.requireAuthentication) return LegacyRead.NeedsAuthentication
        val alias = if (item.usesKeystoreSuffix) LegacyConstants.UNAUTHENTICATED_ALIAS else LegacyConstants.BASE_ALIAS
        val key = keystoreOrNull { Keystore.existingKey(alias) } ?: return LegacyRead.Unreadable(SecureText.NO_KEYSTORE_KEY)
        val cipher = try {
            Keystore.decryptCipher(key, item.iv, item.tagBits)
        } catch (failure: GeneralSecurityException) {
            return LegacyRead.Unreadable(SecureText.DECRYPTION_FAILED)
        } catch (failure: ProviderException) {
            return LegacyRead.Unreadable(SecureText.DECRYPTION_FAILED)
        }
        return finish(cipher, item, asBytes)
    }

    private fun readAuthenticated(name: String, authenticate: (Cipher) -> Cipher?, asBytes: Boolean): LegacyRead {
        val item = when (val parsed = item(name)) {
            is ParsedItem.Done -> return parsed.read
            is ParsedItem.Found -> parsed
        }
        if (!item.requireAuthentication) return read(name, asBytes)
        val key = keystoreOrNull { Keystore.existingKey(LegacyConstants.AUTHENTICATED_ALIAS) }
            ?: return LegacyRead.Unreadable(SecureText.NO_KEYSTORE_KEY)
        val cipher = try {
            Keystore.decryptCipher(key, item.iv, item.tagBits)
        } catch (failure: KeyPermanentlyInvalidatedException) {
            return LegacyRead.Unreadable(SecureText.KEY_INVALIDATED)
        } catch (failure: GeneralSecurityException) {
            return LegacyRead.Unreadable(SecureText.DECRYPTION_FAILED)
        } catch (failure: ProviderException) {
            return LegacyRead.Unreadable(SecureText.DECRYPTION_FAILED)
        }
        val unlocked = authenticate(cipher) ?: return LegacyRead.NeedsAuthentication
        return finish(unlocked, item, asBytes)
    }

    fun forgetAll() {
        preferences.edit().clear().commitOrThrow()
        context.deleteSharedPreferences(LegacyConstants.PREFERENCES_FILE)
        for (alias in LegacyConstants.ALL_ALIASES) Keystore.delete(alias)
    }

    private fun finish(cipher: Cipher, item: ParsedItem.Found, asBytes: Boolean): LegacyRead {
        val plaintext = keystoreOrNull { cipher.doFinal(item.ciphertext) } ?: return LegacyRead.Unreadable(SecureText.DECRYPTION_FAILED)
        if (asBytes) return LegacyRead.Bytes(plaintext)
        return LegacyRead.Value(String(plaintext, Charsets.UTF_8))
    }

    private fun item(name: String): ParsedItem {
        val stored = try {
            preferences.getString(LegacyConstants.KEYCHAIN_SERVICE + LegacyConstants.KEY_SEPARATOR + name, null)
        } catch (failure: ClassCastException) {
            return ParsedItem.Done(LegacyRead.Unreadable(SecureText.NOT_TEXT))
        } ?: return ParsedItem.Done(LegacyRead.Missing)
        return try {
            val json = JSONObject(stored)
            if (json.optString(LegacyConstants.SCHEME_FIELD) != LegacyConstants.SCHEME_AES) return ParsedItem.Done(LegacyRead.Unreadable(SecureText.UNKNOWN_SCHEME))
            val tagBits = json.getInt(LegacyConstants.TAG_LENGTH_FIELD)
            if (tagBits < SecureConstants.MIN_GCM_TAG_BITS) return ParsedItem.Done(LegacyRead.Unreadable(SecureText.SHORT_TAG))
            ParsedItem.Found(
                ciphertext = Base64.decode(json.getString(LegacyConstants.CIPHERTEXT_FIELD), Base64.DEFAULT),
                iv = Base64.decode(json.getString(LegacyConstants.IV_FIELD), Base64.DEFAULT),
                tagBits = tagBits,
                requireAuthentication = json.optBoolean(LegacyConstants.REQUIRE_AUTHENTICATION_FIELD, false),
                usesKeystoreSuffix = json.optBoolean(LegacyConstants.USES_KEYSTORE_SUFFIX_FIELD, false),
            )
        } catch (failure: JSONException) {
            ParsedItem.Done(LegacyRead.Unreadable(SecureText.NOT_JSON))
        } catch (failure: IllegalArgumentException) {
            ParsedItem.Done(LegacyRead.Unreadable(SecureText.BAD_ENCODING))
        }
    }

    private sealed interface ParsedItem {
        class Done(val read: LegacyRead) : ParsedItem

        class Found(
            val ciphertext: ByteArray,
            val iv: ByteArray,
            val tagBits: Int,
            val requireAuthentication: Boolean,
            val usesKeystoreSuffix: Boolean,
        ) : ParsedItem
    }
}
