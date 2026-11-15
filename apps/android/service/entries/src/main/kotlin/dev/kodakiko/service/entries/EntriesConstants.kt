package dev.kodakiko.service.entries

object EntriesConstants {
    const val EDIT_RETRY_ATTEMPTS = 5
    const val FIRST_REVISION = 1L
    const val NEVER_POSTED_REVISION = 0L
    const val NOT_STARTED_SEQ = 0L
    const val NOTHING_ATTEMPTED = 0L
    const val FALLBACK_MAX_MEDIA_PER_ENTRY = 20L
    const val FALLBACK_MAX_UPLOAD_BYTES = 3_758_096_384L
    const val KEY_BINDING_SEPARATOR = "/"
    const val MS_PER_MINUTE = 60_000L
}

object ImportConstants {
    const val PHOTO_POSTER_TIME_MS = 0L
    const val POSTER_MAX_TIME_MS = 1_000L
    const val POSTER_DURATION_DIVISOR = 2L
    const val NO_DURATION_MS = 0L
    const val READ_ONLY_MODE = "r"
    const val VOICE_THUMBNAIL_SIZE = 256
    const val VOICE_THUMBNAIL_BASE64 =
        "UklGRvYEAABXRUJQVlA4IOoEAACwLQCdASoAAQABPm02l0ekI6IhKLToAIANiWNu9kAYgl9Fjr9Z/JbZz+YfkV/AP2A5S7gl/NLBesR+IpwHmAfoB03/MB5UXUAdK76AHSo/s7lUnkb+Qdtf0AfFr2DfB3gG2EYA4gPoBrA3avP4/K/no9wPYY8wDqAP1VGPuVoYe3crQw9u5Whh7dytDD27laGHt3K0MPbuVoYe3crPxznSIiD9iVp42Q0Ty7Ysl4+CheUhjZv48JLH3cisljHe/csdClKFZ8KjQcLfKL4UrNUtcZGhNRQ5juVhIpjqsrgSRo/pKp9BWkrjqOFKeScjmmYfvfjj1UhvhplVHyi+FWyJKGaSkWN8rWwq1xYaaWUiBmzh2egZp0oN/GRCBB7ULvXUcq2cB3GMCznwOanlFFUy6TXDfWKvvB+hEZm4WBv37lleamFK/cnCxd/QiMzcLAyjOzRPLkB6IfXIYnt3K0MPcHQluYB3nLcwDvOW5gHectzAO85WgAD+/WHnDfym5pAqn3lsQUAAAAAAJ8mwlFmZAYIes98TwHwv3Bimu2KkoEiPG5fsggtDGD4gP7iPn9vbjHCvmeF8W2BRlXTTxO0T99WL6oHWA6ji4Tn3gQR0kq+NYWVO9SA+yLqSZXxCh4VJQJEeMmQQWgHA77tgLkXSEgOUyrM+hB2XJ2KCdyLwNluZm2meadH99ljswlFf/8nqiPsvMs2fMuFs4TaTpQxwyjGQQ+M9TZRYlFRwc3ZnORvdSZxvnnBHWOm90rQDjf9St7f8L0cf2+Tf2HxWUF022VV5cNIxY8FmaKzY++twJyzOMkpRUcHF9dRWTSsvwFscSqTAP+FV1sUduT7RakQ4syUX154LEX5nb/ZqXsbQp2j7Ba16gd9ljswlFf/8nqiPsvMs2fMuFs4TaTpRNqEi/RL2RadUHtZcURa6jcIGfrk79ua9I4yfGkT99WL6oHWA6ji4Tn3hSWNsRUFm89VJ8bJpJw4iPjG+hLiB2gfqiuvE6US1dW4ZcTuuR/oWgwZ0S3KJgEl4G2VV5cNIxY8FBrG2IqDCuU3vwoqqPiL9EvZFp1Qe1lxRFrqNwgZ+uTv25r0jjJ8aRP31YvqgdYDqOLhOfeTQwXsg3xqD8L6gVAuZUjnWWZrrLkY6GScRxBru5rS0COfAZXp3WB+KIfhfUCoF0dTRX2rs6Er/yboCx4EzVwAzKQcjuXnVTDGs0bWmzWRW3Yam3QGgagvrp4KJ6okvxVVylFRwfQ953o+irJWZcUnmXHbBU+LUmuci6R+/pjBeyDfkAO17U3Cq0ypQeydPhCKOCEXOxosfGRp9jxUzXpVwDgKvjxlcYEFVcNoAiTK5ApGn/cMBFq54uLSvMZ4jLfGaxjhBUwDTOg0D47SAXzbOAtDnOAzDtpI1VszXn9DTsst2qVxcFEarSI/uI+4oGia5nKnhljlmCcsJaa5jkVWldKgNP5jX1WDfKjgX2GacfZU1uM3ltKpBV+/gkIKGD+t2oj3M5iCJgknoFE9FxBdK0smk7mvjUAhMwEWrnWRfuEqy9hzZjhRs8MuoB3pc220ZH6l3y5g/FGC52M7zeU9vvRGRp9jxUzXpVwDgKvjxlcYEVbcIzD8SUaxSHCrh7dsBdhGgyiB3Vr5GB57/m3kKTEKK7+tfzDYHTB1n3ljAAAAAAAAA"
}

object PickerExifKeys {
    const val DATE_TIME_ORIGINAL = "DateTimeOriginal"
    const val OFFSET_TIME_ORIGINAL = "OffsetTimeOriginal"
    const val GPS_LATITUDE = "GPSLatitude"
    const val GPS_LATITUDE_REF = "GPSLatitudeRef"
    const val GPS_LONGITUDE = "GPSLongitude"
    const val GPS_LONGITUDE_REF = "GPSLongitudeRef"
    const val ORIENTATION = "Orientation"
    const val FRACTION_SEPARATOR = "/"
    const val PART_SEPARATOR = ","
    const val COORDINATE_PARTS = 3
    const val JSON_TRUE = "true"
    const val JSON_FALSE = "false"
}

object OutboxPayloadKeys {
    const val ENTRY_ID = "entryId"
    const val MEDIA_ID = "mediaId"
}

object EntriesText {
    const val ACCOUNT_LOCKED = "The journal is locked."
    const val KEY_UNAVAILABLE = "The key for this item is not available on this device."
    const val EMPTY_TRAY = "Add at least one photo or video first."
    const val READ_ONLY = "This entry is read-only."
    const val NOT_FOUND = "This entry is not on this device."
    const val TRAY_CHANGED = "The photos and videos changed while saving. Try again."
    const val CHANGED_ELSEWHERE = "This entry kept changing while saving. Try again."
    const val MEDIA_EDIT_NOT_ALLOWED = "Add or remove photos and videos with their own actions."
    const val NO_MEDIA_TO_ADD = "Choose at least one photo or video to add."
    const val HOME_MISSING = "The home journal has not synced to this device yet."

    fun tooManyMedia(max: Long): String = "An entry can hold up to $max photos and videos."
}
