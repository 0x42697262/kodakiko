package dev.kodakiko.e2e

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

object E2eConstants {
    const val NODE_COMMAND = "node"
    const val NODE_IMPORT_FLAG = "--import"
    const val NODE_VERSION_FLAG = "--version"
    const val RESOLVE_HOOKS = "./support/kotlin-resolve-hooks.ts"
    const val LAUNCHER = "support/kotlin-server.ts"
    const val E2E_DIRECTORY = "tests/e2e"
    const val REPO_MARKER = "AGENTS.md"
    const val ANDROID_DIRECTORY = "apps/android"
    const val GARAGE_KEY_FILE = "apps/server/test/.garage-test-key.json"
    const val LOOPBACK = "127.0.0.1"
    const val POSTGRES_PORT = 55432
    const val GARAGE_PORT = 53900
    const val REACHABILITY_TIMEOUT_MS = 2_000
    const val LAUNCH_TIMEOUT_SECONDS = 60L
    const val SHUTDOWN_TIMEOUT_SECONDS = 30L
    const val BASE_URL_FIELD = "baseUrl"
    const val CONTROL_URL_FIELD = "controlUrl"
    const val STACK_UNREACHABLE =
        "The docker test stack is not reachable (postgres 127.0.0.1:55432, garage 127.0.0.1:53900, " +
            "apps/server/test/.garage-test-key.json, node). Start it with: " +
            "docker compose -f infra/compose/docker-compose.test.yml up -d"

    const val E2E_PASSWORD = "correct horse battery staple"
    const val NEW_PASSWORD = "another long passphrase here"
    const val USERNAME_PREFIX = "e2e-"
    const val USERNAME_RANDOM_BYTES = 4
    const val ID_BYTES = 16
    const val DEVICE_LABEL = "Test client"
    const val HOME_COLLECTION_NAME = "Journal"
    const val SERVER_URL_TIMEOUT_MS = 20_000L
    const val SEED_TRAY_LIMIT = 20
    const val MEDIA_FILE_PREFIX = "media-"
    const val MEDIA_FILE_SUFFIX = ".jpg"
    const val IMPORT_TEMP_PREFIX = "e2e-import-"
    const val DEVICE_DIRECTORY_PREFIX = "device-"
    const val FILES_DIRECTORY = "files"
    const val CACHE_DIRECTORY = "cache"
    const val PROBE_WIDTH = 640
    const val PROBE_HEIGHT = 480
    const val VIDEO_DURATION_MS = 2000L
    const val THUMBNAIL_BASE64URL = "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD-JaQAA3AAAAAA"
    const val THUMBNAIL_SIDE = 1
    const val LARGE_UPLOAD_THRESHOLD_BYTES = 104_857_600L

    const val KIBIBYTE = 1024
    const val MEBIBYTE = 1_048_576
    const val MULTIPART_MEDIA_BYTES = 10 * MEBIBYTE
    const val SMALL_MEDIA_BYTES = 2 * KIBIBYTE
    val JPEG_START_OF_SCAN: ByteArray = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte())

    const val SETTLE_DEADLINE_MS = 90_000L
    const val SETTLE_POLL_MS = 150L
    val TEST_TIMEOUT: Duration = 3.minutes

    const val TINY_QUOTA_BYTES = 1L
    const val QUOTA_EXCEEDED_CODE = "QUOTA_EXCEEDED"
    const val DIFF_PATH_FRAGMENT = "/diff?since="
    const val FROM_ZERO_SUFFIX = "0&"
    const val PATCH_METHOD = "PATCH"
    const val POST_METHOD = "POST"
    const val PUT_METHOD = "PUT"
    const val GET_METHOD = "GET"
    const val PART_NUMBER_QUERY = "partNumber="
    const val DROPPED_RESPONSE_MESSAGE = "response dropped by the e2e fetcher"
    const val OFFLINE_MESSAGE = "the e2e fetcher is offline"
    const val REQUEST_SEPARATOR = " "

    const val EARLIER_EDIT_UTC = "2030-01-01T10:00:00.000Z"
    const val LATER_EDIT_UTC = "2030-01-01T11:00:00.000Z"
    const val FIRST_REVISION = 1L
    const val SECOND_REVISION = 2L
    const val THIRD_REVISION = 3L
    const val LOST_RESPONSE_COUNT = 3
    const val NO_REVISION = 0L

    const val UPDATE_ENTRY_META_KIND = "updateEntryMeta"
    const val TRASH_ENTRY_KIND = "trashEntry"
    const val DELETE_ENTRY_KIND = "deleteEntry"
    const val CREATE_ENTRY_KIND = "createEntry"
    const val ENTRY_ID_FIELD = "entryId"

    const val FLAG_SHARING = "FLAG_SHARING"
    const val FLAG_PUBLIC_LINKS = "FLAG_PUBLIC_LINKS"
    const val SIGNUP_MODE = "SIGNUP_MODE"
    const val SIGNUP_INVITE = "invite"
    const val FLAG_ON = "true"
    const val FLAG_OFF = "false"

    const val JPEG_PREFIX = 0xff
    const val JPEG_SOI = 0xd8
    const val JPEG_SOS = 0xda
    const val JPEG_APP_FIRST = 0xe1
    const val JPEG_APP_LAST = 0xef
    const val JPEG_HEADER_BYTES = 2
    const val JPEG_LENGTH_BYTES = 2
    const val BYTE_SHIFT = 8
    const val BYTE_MASK = 0xff
    const val PLACEHOLDER_EDGE = 1
    const val NOT_A_JPEG = "not a JPEG"

    const val STREAM_MAGIC_BYTES = 4
}

object ControlRoutes {
    const val ENTRY = "/entry"
    const val QUOTA = "/quota"
    const val REWIND = "/rewind"
    const val PRUNE_TOMBSTONES = "/prune-tombstones"
    const val OPEN_LINK = "/open-link"
    const val PHOTO_WITH_GPS = "/media/photo-with-gps"
    const val VIDEO_WITH_LOCATION = "/media/video-with-location"
    const val INVITE = "/invite"
    const val CLOSE = "/close"
}

object ControlFields {
    const val ENTRY_ID = "entryId"
    const val ENTRY = "entry"
    const val REVISION = "revision"
    const val METADATA_BLOB = "metadataBlob"
    const val TRASHED_AT = "trashedAt"
    const val USER_ID = "userId"
    const val QUOTA_BYTES = "quotaBytes"
    const val COLLECTION_ID = "collectionId"
    const val URL = "url"
    const val READ = "read"
    const val OK = "ok"
    const val API_ERROR = "apiError"
    const val CODE = "code"
    const val TITLE = "title"
    const val ITEMS = "items"
    const val KIND = "kind"
    const val NOTE = "note"
    const val THUMBNAIL_BYTES = "thumbnailBytes"
    const val GPS = "gps"
    const val DATE_TIME_ORIGINAL = "dateTimeOriginal"
    const val LOCATION = "location"
    const val CREATION_UTC = "creationUtc"
    const val TOKEN = "token"
    const val INVITE_URL = "inviteUrl"
    const val MEDIA_ID = "mediaId"
    const val JSON_MEDIA_TYPE = "application/json"
}
