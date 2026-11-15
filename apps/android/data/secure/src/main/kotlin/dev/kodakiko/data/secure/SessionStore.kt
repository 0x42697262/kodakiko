package dev.kodakiko.data.secure

import android.content.Context

class SessionStore(context: Context) {
    private val preferences = SealedPreferences(context, SecureConstants.SESSIONS_FILE, SecureConstants.STORE_ALIAS)

    fun save(accountHash: String, token: String) {
        preferences.put(name(accountHash), token)
    }

    fun load(accountHash: String): String? = preferences.get(name(accountHash))

    fun clear(accountHash: String) {
        preferences.remove(name(accountHash))
    }

    private fun name(accountHash: String): String = SecureConstants.SESSION_PREFIX + requireAccountHash(accountHash)
}
