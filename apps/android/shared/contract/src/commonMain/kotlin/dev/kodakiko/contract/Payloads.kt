package dev.kodakiko.contract

data class ThumbnailDescriptor(val width: Long, val height: Long, val plaintextBytes: Long) {
    companion object {
        const val MIME_TYPE = "image/webp"
    }
}

data class MediaDescriptorV1(
    val mediaId: String,
    val kind: MediaKind,
    val mimeType: String,
    val width: Long,
    val height: Long,
    val durationMs: Long?,
    val originalPlaintextBytes: Long,
    val thumbnail: ThumbnailDescriptor,
    val capturedAtUtc: String?,
    val originalFileName: String?,
    val caption: String? = null,
)

data class EntryLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Double?, val source: LocationSource)

data class EntryMetadataV1(
    val takenAtUtc: String,
    val utcOffsetMinutes: Long,
    val timeZone: String?,
    val note: String,
    val tags: List<String>,
    val location: EntryLocation?,
    val media: List<MediaDescriptorV1>,
    val createdAtUtc: String,
    val editedAtUtc: String,
    val favorite: Boolean? = null,
)

data class CollectionMetadataV1(val name: String, val createdAtUtc: String)

internal object PayloadCodecs {
    private const val SCHEMA_VERSION = "schemaVersion"
    private val isoTime = CommonCodecs.isoTime
    private val nullableIsoTime: Codec<String?> = isoTime.nullable()
    private val positive = CommonCodecs.positive
    private val entryVersion: Codec<Unit> = NumberLiteralCodec(ENTRY_METADATA_SCHEMA_VERSION)
    private val collectionVersion: Codec<Unit> = NumberLiteralCodec(COLLECTION_METADATA_SCHEMA_VERSION)
    private val thumbnailMimeType: Codec<Unit> = StringLiteralCodec(ThumbnailDescriptor.MIME_TYPE)
    private val mediaKind: Codec<MediaKind> = enumCodec(MediaKind.entries)
    private val mimeType: Codec<String> = StringCodec(maxLength = MIME_TYPE_MAX_CHARS)
    private val durationMs: Codec<Long?> = CommonCodecs.count.nullable()
    private val fileName: Codec<String?> = StringCodec(maxLength = FILE_NAME_MAX_CHARS).nullable()
    private val caption: Codec<String?> = StringCodec(minLength = 1, maxLength = CAPTION_MAX_CHARS).optional()
    private val utcOffset: Codec<Long> = IntegerCodec(minimum = UTC_OFFSET_MINUTES_MIN, maximum = UTC_OFFSET_MINUTES_MAX)
    private val timeZone: Codec<String?> = StringCodec(maxLength = TIME_ZONE_MAX_CHARS).nullable()
    private val note: Codec<String> = StringCodec(maxLength = NOTE_MAX_CHARS)
    private val latitude: Codec<Double> = DoubleCodec(LATITUDE_MIN, LATITUDE_MAX)
    private val longitude: Codec<Double> = DoubleCodec(LONGITUDE_MIN, LONGITUDE_MAX)
    private val accuracy: Codec<Double?> = DoubleCodec(minimum = 0.0).nullable()
    private val locationSource: Codec<LocationSource> = enumCodec(LocationSource.entries)
    private val favorite: Codec<Boolean?> = CommonCodecs.boolean.optional()
    private val collectionName: Codec<String> = StringCodec(minLength = 1, maxLength = COLLECTION_NAME_MAX_CHARS)

    val tagName: Codec<String> = StringCodec(minLength = 1, maxLength = TAG_MAX_CHARS)
    private val tags: Codec<List<String>> = tagName.array(maxItems = TAGS_PER_ENTRY_MAX)

    private val thumbnail: Codec<ThumbnailDescriptor> = objectCodec(
        {
            val width = field("width", positive)
            val height = field("height", positive)
            field("mimeType", thumbnailMimeType)
            ThumbnailDescriptor(width, height, field("plaintextBytes", positive))
        },
        {
            field("width", positive, it.width)
            field("height", positive, it.height)
            field("mimeType", thumbnailMimeType, Unit)
            field("plaintextBytes", positive, it.plaintextBytes)
        },
    )

    val mediaDescriptor: Codec<MediaDescriptorV1> = objectCodec(
        {
            MediaDescriptorV1(
                mediaId = field("mediaId", CommonCodecs.id),
                kind = field("kind", mediaKind),
                mimeType = field("mimeType", mimeType),
                width = field("width", positive),
                height = field("height", positive),
                durationMs = field("durationMs", durationMs),
                originalPlaintextBytes = field("originalPlaintextBytes", CommonCodecs.count),
                thumbnail = field("thumbnail", thumbnail),
                capturedAtUtc = field("capturedAtUtc", nullableIsoTime),
                originalFileName = field("originalFileName", fileName),
                caption = field("caption", caption),
            )
        },
        {
            field("mediaId", CommonCodecs.id, it.mediaId)
            field("kind", mediaKind, it.kind)
            field("mimeType", mimeType, it.mimeType)
            field("width", positive, it.width)
            field("height", positive, it.height)
            field("durationMs", durationMs, it.durationMs)
            field("originalPlaintextBytes", CommonCodecs.count, it.originalPlaintextBytes)
            field("thumbnail", thumbnail, it.thumbnail)
            field("capturedAtUtc", nullableIsoTime, it.capturedAtUtc)
            field("originalFileName", fileName, it.originalFileName)
            field("caption", caption, it.caption)
        },
    )

    private val location: Codec<EntryLocation?> = objectCodec<EntryLocation>(
        {
            EntryLocation(
                field("latitude", latitude),
                field("longitude", longitude),
                field("accuracyMeters", accuracy),
                field("source", locationSource),
            )
        },
        {
            field("latitude", latitude, it.latitude)
            field("longitude", longitude, it.longitude)
            field("accuracyMeters", accuracy, it.accuracyMeters)
            field("source", locationSource, it.source)
        },
    ).nullable()

    private val mediaList: Codec<List<MediaDescriptorV1>> = mediaDescriptor.array(1, MEDIA_PER_ENTRY_HARD_MAX)

    val entryMetadata: Codec<EntryMetadataV1> = objectCodec(
        {
            field(SCHEMA_VERSION, entryVersion)
            EntryMetadataV1(
                takenAtUtc = field("takenAtUtc", isoTime),
                utcOffsetMinutes = field("utcOffsetMinutes", utcOffset),
                timeZone = field("timeZone", timeZone),
                note = field("note", note),
                tags = field("tags", tags),
                location = field("location", location),
                media = field("media", mediaList),
                createdAtUtc = field("createdAtUtc", isoTime),
                editedAtUtc = field("editedAtUtc", isoTime),
                favorite = field("favorite", favorite),
            )
        },
        {
            field(SCHEMA_VERSION, entryVersion, Unit)
            field("takenAtUtc", isoTime, it.takenAtUtc)
            field("utcOffsetMinutes", utcOffset, it.utcOffsetMinutes)
            field("timeZone", timeZone, it.timeZone)
            field("note", note, it.note)
            field("tags", tags, it.tags)
            field("location", location, it.location)
            field("media", mediaList, it.media)
            field("createdAtUtc", isoTime, it.createdAtUtc)
            field("editedAtUtc", isoTime, it.editedAtUtc)
            field("favorite", favorite, it.favorite)
        },
    )

    val collectionMetadata: Codec<CollectionMetadataV1> = objectCodec(
        {
            field(SCHEMA_VERSION, collectionVersion)
            CollectionMetadataV1(field("name", collectionName), field("createdAtUtc", isoTime))
        },
        {
            field(SCHEMA_VERSION, collectionVersion, Unit)
            field("name", collectionName, it.name)
            field("createdAtUtc", isoTime, it.createdAtUtc)
        },
    )
}
