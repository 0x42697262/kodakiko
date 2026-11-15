package dev.kodakiko.data.secure

import android.content.Context

class GlobalStore(context: Context) {
    private val preferences = SealedPreferences(context, SecureConstants.GLOBAL_FILE, SecureConstants.STORE_ALIAS)

    fun serverUrl(): String? = preferences.get(SecureConstants.GLOBAL_SERVER_URL)

    fun setServerUrl(url: String) {
        preferences.put(SecureConstants.GLOBAL_SERVER_URL, url)
    }

    fun clearServerUrl() {
        preferences.remove(SecureConstants.GLOBAL_SERVER_URL)
    }

    fun activeAccount(): ActiveAccount? = preferences.get(SecureConstants.GLOBAL_ACTIVE_ACCOUNT)?.let(ActiveAccount::parse)

    fun setActiveAccount(account: ActiveAccount) {
        preferences.put(SecureConstants.GLOBAL_ACTIVE_ACCOUNT, account.toJson())
    }

    fun clearActiveAccount() {
        preferences.remove(SecureConstants.GLOBAL_ACTIVE_ACCOUNT)
    }

    fun serverConfigJson(): String? = preferences.get(SecureConstants.GLOBAL_SERVER_CONFIG)

    fun setServerConfigJson(json: String) {
        preferences.put(SecureConstants.GLOBAL_SERVER_CONFIG, json)
    }

    fun clearServerConfigJson() {
        preferences.remove(SecureConstants.GLOBAL_SERVER_CONFIG)
    }
}
