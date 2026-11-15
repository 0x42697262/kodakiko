package dev.kodakiko.data.migration

internal object LegacyKeys {
    const val SERVER = "kodakiko.server"
    const val ACTIVE_ACCOUNT = "kodakiko.activeAccount"
    const val SERVER_CONFIG = "kodakiko.serverConfig"
    const val SESSION_PREFIX = "kodakiko.session."
    const val MASTER_KEY_PREFIX = "kodakiko.mk."
}

internal object MigrationConstants {
    const val MARKER_SCHEMA_VERSION = 1
    const val MARKER_SCHEMA_VERSION_FIELD = "schemaVersion"
    const val MARKER_AT_FIELD = "at"
    const val ISO_TIME_PATTERN = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'"
}
