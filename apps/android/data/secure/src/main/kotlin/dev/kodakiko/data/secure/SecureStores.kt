package dev.kodakiko.data.secure

import android.content.Context

class SecureStores(private val context: Context) {
    fun wipeAll() {
        for (file in SecureConstants.OWNED_FILES) {
            context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commitOrThrow()
            context.deleteSharedPreferences(file)
        }
        for (alias in SecureConstants.OWNED_ALIASES) Keystore.delete(alias)
    }
}

internal fun requireAccountHash(accountHash: String): String {
    require(SecureConstants.ACCOUNT_HASH.matches(accountHash)) { SecureText.invalidAccountHash(accountHash) }
    return accountHash
}
