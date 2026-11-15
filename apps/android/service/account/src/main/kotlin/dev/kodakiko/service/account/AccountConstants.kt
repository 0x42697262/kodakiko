package dev.kodakiko.service.account

object AccountConstants {
    const val FIRST_WORD_POSITION = 1
    const val LOCAL_ACCOUNT_KEY_PREFIX = "local"
    const val ACCOUNT_KEY_SEPARATOR = "/"
    const val PORT_SEPARATOR = ":"
    const val IPV6_OPEN = "["
    const val IPV6_CLOSE = "]"
    const val ADMIN_ROLE = "admin"
}

object ServerUrlConstants {
    val LOCAL_HTTP_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "10.0.2.2")
    val SCHEME_PATTERN = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)
    const val SCHEME_SEPARATOR = "://"
    const val DEFAULT_SCHEME = "https://"
    const val HTTP_SCHEME = "http"
    const val HTTPS_SCHEME = "https"
    const val INVITE_PATH = "/invite"
    const val ORIGIN_PATH = "/"
    const val BYTE_ORDER_MARK = "\uFEFF"
}

object StoreConstants {
    val DATABASE_SIDE_FILE_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")
}

object KdfParamsKeys {
    const val SALT = "salt"
    const val OPS_LIMIT = "opsLimit"
    const val MEM_LIMIT_BYTES = "memLimitBytes"
}

object AccountText {
    const val ROW_STORE_NOT_OPEN = "the row store is not open"
    const val FILE_STORE_NOT_OPEN = "the file store is not open"

    fun storedValueInvalid(key: String): String = "stored value for $key has the wrong shape"
}
