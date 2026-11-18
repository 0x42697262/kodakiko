package dev.kodakiko.data.db

internal object DbConstants {
    const val SCHEMA_VERSION = 1
    const val DATABASE_DIRECTORY = "SQLite"
    const val DATABASE_FILE_PREFIX = "kodakiko-"
    const val DATABASE_FILE_EXTENSION = ".db"
    const val PRAGMA_PREFIX = "PRAGMA "
    const val USER_VERSION_PRAGMA = "user_version"
    const val PRAGMA_RESULT_COLUMN = 0
    const val PRAGMA_ARGUMENT_COUNT = 0
    val PRAGMA_NAME = Regex("^[a-z_]+$")
    val ACCOUNT_HASH = Regex("^[0-9a-f]{16}$")
}

internal object DbText {
    fun newerSchema(found: Long): String = "The local database was created by a newer version of the app (schema $found)."

    fun unknownWireValue(type: String, value: String): String = "Unknown $type value in the local database: $value"

    fun invalidPragma(name: String): String = "Not a pragma name: $name"

    fun invalidAccountHash(hash: String): String = "Not an account hash: $hash"

    fun invalidDeviceSettings(fields: List<String>): String = "Invalid device settings: ${fields.joinToString(", ")}"

    fun deviceSettingsNotObject(): String = "Device settings must be a JSON object."

    fun jsonSyntax(position: Int): String = "Invalid JSON at position $position"
}
