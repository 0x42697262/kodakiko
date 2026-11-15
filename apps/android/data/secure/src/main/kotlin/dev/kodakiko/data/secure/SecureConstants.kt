package dev.kodakiko.data.secure

internal object SecureConstants {
    const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val AES_GCM = "AES/GCM/NoPadding"
    const val AES_KEY_BITS = 256
    const val GCM_TAG_BITS = 128
    const val MIN_GCM_TAG_BITS = 96
    const val BIOMETRIC_AUTH_TIMEOUT_SECONDS = 0

    const val ITEM_SCHEMA_VERSION = 1
    const val ITEM_SCHEMA_VERSION_FIELD = "schemaVersion"
    const val ITEM_IV_FIELD = "iv"
    const val ITEM_CIPHERTEXT_FIELD = "ct"
    const val ITEM_MODE_FIELD = "mode"
    const val AAD_SEPARATOR = "/"

    const val STORE_ALIAS = "kodakiko.secure.store.v1"
    const val VAULT_DEVICE_ALIAS = "kodakiko.secure.vault.device.v1"
    const val VAULT_BIOMETRIC_ALIAS = "kodakiko.secure.vault.biometric.v1"
    val OWNED_ALIASES = listOf(STORE_ALIAS, VAULT_DEVICE_ALIAS, VAULT_BIOMETRIC_ALIAS)

    const val GLOBAL_FILE = "kodakiko.secure.global"
    const val SESSIONS_FILE = "kodakiko.secure.sessions"
    const val VAULT_FILE = "kodakiko.secure.vault"
    val OWNED_FILES = listOf(GLOBAL_FILE, SESSIONS_FILE, VAULT_FILE)

    const val GLOBAL_SERVER_URL = "serverUrl"
    const val GLOBAL_ACTIVE_ACCOUNT = "activeAccount"
    const val GLOBAL_SERVER_CONFIG = "serverConfig"
    const val SESSION_PREFIX = "session."
    const val MASTER_KEY_PREFIX = "mk."
    val ACCOUNT_HASH = Regex("^[0-9a-f]{16}$")
}

internal object LegacyConstants {
    const val PREFERENCES_FILE = "SecureStore"
    const val KEYCHAIN_SERVICE = "key_v1"
    const val KEY_SEPARATOR = "-"
    const val ALIAS_SEPARATOR = ":"
    const val UNAUTHENTICATED_SUFFIX = "keystoreUnauthenticated"
    const val AUTHENTICATED_SUFFIX = "keystoreAuthenticated"
    const val SCHEME_FIELD = "scheme"
    const val SCHEME_AES = "aes"
    const val CIPHERTEXT_FIELD = "ct"
    const val IV_FIELD = "iv"
    const val TAG_LENGTH_FIELD = "tlen"
    const val REQUIRE_AUTHENTICATION_FIELD = "requireAuthentication"
    const val USES_KEYSTORE_SUFFIX_FIELD = "usesKeystoreSuffix"

    val BASE_ALIAS = SecureConstants.AES_GCM + ALIAS_SEPARATOR + KEYCHAIN_SERVICE
    val UNAUTHENTICATED_ALIAS = BASE_ALIAS + ALIAS_SEPARATOR + UNAUTHENTICATED_SUFFIX
    val AUTHENTICATED_ALIAS = BASE_ALIAS + ALIAS_SEPARATOR + AUTHENTICATED_SUFFIX
    val ALL_ALIASES = listOf(UNAUTHENTICATED_ALIAS, AUTHENTICATED_ALIAS, BASE_ALIAS)
}

internal object ActiveAccountConstants {
    const val CURRENT_SCHEMA_VERSION = 2
    const val SCHEMA_VERSION = "schemaVersion"
    const val ACCOUNT_KEY = "accountKey"
    const val USERNAME = "username"
    const val MODE = "mode"
}

internal object SecureText {
    const val NOT_JSON = "The legacy item is not JSON."
    const val BAD_ENCODING = "The legacy item has an invalid Base64 field."
    const val REDACTED_VALUE = "Value(<redacted>)"
    const val UNKNOWN_SCHEME = "The legacy item uses an unsupported encryption scheme."
    const val NO_KEYSTORE_KEY = "The Keystore key of the legacy item is missing."
    const val SHORT_TAG = "The legacy item has a too short authentication tag."
    const val NOT_TEXT = "The legacy item is not a text value."
    const val DECRYPTION_FAILED = "The legacy item could not be decrypted."
    const val KEY_INVALIDATED = "The Keystore key of the legacy item was invalidated."
    const val PREFERENCES_NOT_SAVED = "The secure preferences could not be saved."

    fun invalidAccountHash(hash: String): String = "Not an account hash: $hash"
}
