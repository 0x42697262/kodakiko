package dev.kodakiko.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.GeneralSecurityException
import java.security.ProviderException

internal class SealedItem(val iv: ByteArray, val ciphertext: ByteArray, val mode: String? = null) {
    fun encode(): String =
        buildJsonObject {
            put(SecureConstants.ITEM_SCHEMA_VERSION_FIELD, SecureConstants.ITEM_SCHEMA_VERSION)
            if (mode != null) put(SecureConstants.ITEM_MODE_FIELD, mode)
            put(SecureConstants.ITEM_IV_FIELD, Base64.encodeToString(iv, Base64.NO_WRAP))
            put(SecureConstants.ITEM_CIPHERTEXT_FIELD, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }.toString()

    companion object {
        fun decode(text: String): SealedItem? =
            try {
                val item = Json.parseToJsonElement(text) as? JsonObject
                val version = (item?.get(SecureConstants.ITEM_SCHEMA_VERSION_FIELD) as? JsonPrimitive)?.intOrNull
                if (item == null || version != SecureConstants.ITEM_SCHEMA_VERSION) {
                    null
                } else {
                    SealedItem(
                        iv = Base64.decode(item.string(SecureConstants.ITEM_IV_FIELD) ?: return null, Base64.NO_WRAP),
                        ciphertext = Base64.decode(item.string(SecureConstants.ITEM_CIPHERTEXT_FIELD) ?: return null, Base64.NO_WRAP),
                        mode = item.string(SecureConstants.ITEM_MODE_FIELD),
                    )
                }
            } catch (failure: IllegalArgumentException) {
                null
            }

        private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

internal fun additionalData(fileName: String, name: String): ByteArray = (fileName + SecureConstants.AAD_SEPARATOR + name).encodeToByteArray()

internal fun SharedPreferences.Editor.commitOrThrow() {
    check(commit()) { SecureText.PREFERENCES_NOT_SAVED }
}

internal inline fun <T> keystoreOrNull(work: () -> T): T? =
    try {
        work()
    } catch (failure: GeneralSecurityException) {
        null
    } catch (failure: ProviderException) {
        null
    }

internal class SealedPreferences(context: Context, private val fileName: String, private val alias: String) {
    private val preferences: SharedPreferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val item = preferences.getString(name, null)?.let(SealedItem::decode) ?: return null
        val key = keystoreOrNull { Keystore.existingKey(alias) } ?: return null
        val plaintext = keystoreOrNull {
            Keystore.decryptCipher(key, item.iv).run {
                updateAAD(additionalData(fileName, name))
                doFinal(item.ciphertext)
            }
        } ?: return null
        return plaintext.decodeToString()
    }

    fun put(name: String, value: String) {
        val cipher = Keystore.encryptCipher(Keystore.key(alias, KeyProtection.NONE))
        cipher.updateAAD(additionalData(fileName, name))
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        preferences.edit().putString(name, SealedItem(cipher.iv, ciphertext).encode()).commitOrThrow()
    }

    fun remove(name: String) {
        preferences.edit().remove(name).commitOrThrow()
    }
}
