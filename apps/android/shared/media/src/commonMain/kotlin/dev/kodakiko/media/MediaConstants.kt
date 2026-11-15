package dev.kodakiko.media

import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.WireEnum

const val SNIFF_HEADER_BYTES = 64
const val EXIF_SCAN_MAX_BYTES = 262_144
const val EXIF_MAX_IFD_ENTRIES = 512
const val BOX_WALK_MAX_BOXES = 64
const val MOOV_MAX_BYTES = 8_388_608L
const val MP4_EPOCH_OFFSET_SECONDS = 2_082_844_800L
const val EXIF_ASCII_MAX_BYTES = 64
const val KEYS_NAME_MAX_BYTES = 256
const val ISO6709_MAX_BYTES = 64
const val MAX_ISO_YEAR = 9999
const val STRIP_COPY_CHUNK_BYTES = 1_048_576

const val MEDIA_BYTE_RADIX = 256
const val MEDIA_UINT8_BYTES = 1
const val MEDIA_UINT16_BYTES = 2
const val MEDIA_UINT32_BYTES = 4
const val MEDIA_UINT64_BYTES = 8
const val MEDIA_MS_PER_SECOND = 1000L
const val MEDIA_MS_PER_MINUTE = 60_000L
const val MEDIA_MINUTES_PER_HOUR = 60
const val MEDIA_SECONDS_PER_MINUTE = 60
const val MEDIA_SECONDS_PER_HOUR = 3600
const val MEDIA_DECIMAL_RADIX = 10

const val FTYP_TAG = "ftyp"
const val FTYP_TAG_OFFSET = 4
const val FTYP_MAJOR_BRAND_OFFSET = 8
const val FTYP_FIRST_COMPATIBLE_OFFSET = 16
const val FTYP_BRAND_BYTES = 4
const val FTYP_MIN_BYTES = 12

val HEIC_BRANDS: Set<String> = setOf("heic", "heix", "heim", "heis")
val HEIF_BRANDS: Set<String> = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")
val MOV_BRANDS: Set<String> = setOf("qt  ")
val MP4_BRANDS: Set<String> = setOf(
    "isom",
    "iso2",
    "iso4",
    "iso5",
    "iso6",
    "mp41",
    "mp42",
    "avc1",
    "M4V ",
    "dash",
    "3gp4",
    "3gp5",
    "3g2a",
)

const val MIME_JPEG = "image/jpeg"
const val MIME_PNG = "image/png"
const val MIME_WEBP = "image/webp"
const val MIME_WEBM = "video/webm"
const val MIME_HEIC = "image/heic"
const val MIME_HEIF = "image/heif"
const val MIME_MOV = "video/quicktime"
const val MIME_MP4 = "video/mp4"

private val JPEG_MAGIC = listOf(0xff, 0xd8, 0xff)
private val PNG_MAGIC = listOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val ASCII_RIFF = listOf(0x52, 0x49, 0x46, 0x46)
private val ASCII_WEBP = listOf(0x57, 0x45, 0x42, 0x50)
private val WEBM_MAGIC = listOf(0x1a, 0x45, 0xdf, 0xa3)
private const val WEBP_TAG_OFFSET = 8

data class MagicPart(val offset: Int, val bytes: List<Int>)

data class MagicSignature(
    val kind: MediaKind,
    val mimeType: String,
    val container: MediaContainer,
    val parts: List<MagicPart>,
)

val SIGNATURES: List<MagicSignature> = listOf(
    MagicSignature(MediaKind.PHOTO, MIME_JPEG, MediaContainer.JPEG, listOf(MagicPart(0, JPEG_MAGIC))),
    MagicSignature(MediaKind.PHOTO, MIME_PNG, MediaContainer.PNG, listOf(MagicPart(0, PNG_MAGIC))),
    MagicSignature(
        MediaKind.PHOTO,
        MIME_WEBP,
        MediaContainer.WEBP,
        listOf(MagicPart(0, ASCII_RIFF), MagicPart(WEBP_TAG_OFFSET, ASCII_WEBP)),
    ),
    MagicSignature(MediaKind.VIDEO, MIME_WEBM, MediaContainer.WEBM, listOf(MagicPart(0, WEBM_MAGIC))),
)

const val TIFF_HEADER_BYTES = 8
const val TIFF_ORDER_OFFSET = 0
const val TIFF_MAGIC_OFFSET = 2
const val TIFF_IFD0_POINTER_OFFSET = 4
const val TIFF_MAGIC = 42L
const val TIFF_LITTLE_ENDIAN_MARK = "II"
const val TIFF_BIG_ENDIAN_MARK = "MM"
const val TIFF_ORDER_MARK_BYTES = 2
const val IFD_COUNT_BYTES = 2
const val IFD_ENTRY_BYTES = 12
const val IFD_ENTRY_TYPE_OFFSET = 2
const val IFD_ENTRY_COUNT_OFFSET = 4
const val IFD_ENTRY_VALUE_OFFSET = 8
const val IFD_INLINE_VALUE_BYTES = 4

const val TIFF_TYPE_ASCII = 2L
const val TIFF_TYPE_SHORT = 3L
const val TIFF_TYPE_LONG = 4L
const val TIFF_TYPE_RATIONAL = 5L
const val RATIONAL_BYTES = 8
const val RATIONAL_DENOMINATOR_OFFSET = 4
const val GPS_COORDINATE_PARTS = 3

const val TAG_ORIENTATION = 0x0112L
const val TAG_EXIF_POINTER = 0x8769L
const val TAG_GPS_POINTER = 0x8825L
const val TAG_DATE_TIME_ORIGINAL = 0x9003L
const val TAG_OFFSET_TIME_ORIGINAL = 0x9011L
const val TAG_GPS_LATITUDE_REF = 1L
const val TAG_GPS_LATITUDE = 2L
const val TAG_GPS_LONGITUDE_REF = 3L
const val TAG_GPS_LONGITUDE = 4L

const val ORIENTATION_MIN = 1
const val ORIENTATION_MAX = 8
const val ORIENTATION_SWAP_MIN = 5
const val LATITUDE_LIMIT = 90.0
const val LONGITUDE_LIMIT = 180.0
const val GPS_NORTH = "N"
const val GPS_SOUTH = "S"
const val GPS_EAST = "E"
const val GPS_WEST = "W"

val EXIF_DATE_PATTERN = Regex("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")
val EXIF_DATE_PARTS_PATTERN = Regex("([0-9]{4}):([0-9]{2}):([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})")
val EXIF_OFFSET_PATTERN = Regex("([+-])([0-9]{2}):([0-9]{2})")
const val NUL_CHAR = '\u0000'
val ISO6709_PATTERN = Regex("([+-][0-9]{2}(?:\\.[0-9]+)?)([+-][0-9]{3}(?:\\.[0-9]+)?)(?:[+-][0-9]+(?:\\.[0-9]+)?)?/?")
const val NEGATIVE_SIGN = "-"
const val ISO6709_LATITUDE_GROUP = 1
const val ISO6709_LONGITUDE_GROUP = 2

const val JPEG_MARKER_PREFIX = 0xffL
const val JPEG_SOI_BYTES = 2
const val JPEG_MARKER_BYTES = 2
const val JPEG_SOI_SECOND_BYTE = 0xd8L
const val JPEG_MARKER_SOS = 0xdaL
const val JPEG_MARKER_EOI = 0xd9L
const val JPEG_MARKER_APP1 = 0xe1L
const val JPEG_MARKER_TEM = 0x01L
const val JPEG_MARKER_RST_MIN = 0xd0L
const val JPEG_MARKER_RST_MAX = 0xd7L
const val JPEG_LENGTH_FIELD_BYTES = 2
const val JPEG_SEGMENT_HEADER_BYTES = 4
const val EXIF_IDENTIFIER = "Exif\u0000\u0000"

const val PNG_SIGNATURE_BYTES = 8
val PNG_SIGNATURE: List<Int> = PNG_MAGIC
const val PNG_CHUNK_HEADER_BYTES = 8
const val PNG_CHUNK_TYPE_OFFSET = 4
const val PNG_CHUNK_TYPE_BYTES = 4
const val PNG_CRC_BYTES = 4
const val PNG_EXIF_CHUNK = "eXIf"
const val PNG_END_CHUNK = "IEND"

const val RIFF_HEADER_BYTES = 12
const val RIFF_CHUNK_HEADER_BYTES = 8
const val RIFF_CHUNK_SIZE_OFFSET = 4
const val RIFF_TAG = "RIFF"
const val RIFF_WEBP_TAG = "WEBP"
const val RIFF_WEBP_TAG_OFFSET = 8
const val WEBP_EXIF_CHUNK = "EXIF"
const val RIFF_PAD_ALIGNMENT = 2

const val BOX_HEADER_BYTES = 8
const val BOX_LARGE_HEADER_BYTES = 16
const val BOX_PEEK_BYTES = 16
const val BOX_TYPE_OFFSET = 4
const val BOX_TYPE_BYTES = 4
const val BOX_LARGE_SIZE_MARKER = 1L
const val BOX_LARGE_SIZE_OFFSET = 8
const val BOX_TO_END_MARKER = 0L
const val BOX_MOOV = "moov"
const val BOX_MVHD = "mvhd"
const val BOX_UDTA = "udta"
const val BOX_META = "meta"
const val BOX_KEYS = "keys"
const val BOX_ILST = "ilst"
const val BOX_DATA = "data"
const val BOX_XYZ = "\u00a9xyz"
const val MVHD_VERSION_1 = 1L
const val MVHD_CREATION_OFFSET = 4
const val MVHD_TIMESCALE_OFFSET_V0 = 12
const val MVHD_DURATION_OFFSET_V0 = 16
const val MVHD_TIMESCALE_OFFSET_V1 = 20
const val MVHD_DURATION_OFFSET_V1 = 24
const val MVHD_DURATION_FIELDS_BYTES = 32
const val ROUNDING_DOUBLE = 2L
const val FULL_BOX_HEADER_BYTES = 4
const val XYZ_LENGTH_BYTES = 2
const val XYZ_LANGUAGE_BYTES = 2
const val KEYS_COUNT_OFFSET = 4
const val KEYS_FIRST_ENTRY_OFFSET = 8
const val KEYS_ENTRY_SIZE_BYTES = 4
const val KEYS_ENTRY_NAMESPACE_BYTES = 4
const val DATA_BOX_VALUE_OFFSET = 8
const val BOX_TRAK = "trak"
const val BOX_TKHD = "tkhd"
const val BOX_MDIA = "mdia"
const val BOX_HDLR = "hdlr"
const val HANDLER_VIDEO = "vide"
const val HDLR_HANDLER_OFFSET = 8
const val HDLR_HANDLER_BYTES = 4
const val TKHD_VERSION_1 = 1L
const val TKHD_MATRIX_OFFSET_V0 = 40
const val TKHD_MATRIX_OFFSET_V1 = 52
const val MATRIX_CELL_BYTES = 4
const val MATRIX_A_OFFSET = 0
const val MATRIX_B_OFFSET = 4
const val MATRIX_C_OFFSET = 12
const val MATRIX_D_OFFSET = 16
const val MATRIX_ONE = 0x10000L
const val UINT32_RANGE = 0x1_0000_0000L
const val INT32_MAX = 0x7fff_ffffL
const val APPLE_LOCATION_KEY = "com.apple.quicktime.location.ISO6709"

data class RotationMatrix(val degrees: VideoRotation, val cells: List<Long>)

val ROTATION_MATRICES: List<RotationMatrix> = listOf(
    RotationMatrix(VideoRotation.DEGREES_0, listOf(MATRIX_ONE, 0L, 0L, MATRIX_ONE)),
    RotationMatrix(VideoRotation.DEGREES_90, listOf(0L, MATRIX_ONE, -MATRIX_ONE, 0L)),
    RotationMatrix(VideoRotation.DEGREES_180, listOf(-MATRIX_ONE, 0L, 0L, -MATRIX_ONE)),
    RotationMatrix(VideoRotation.DEGREES_270, listOf(0L, -MATRIX_ONE, MATRIX_ONE, 0L)),
)

enum class VideoRotation(val degrees: Int) {
    DEGREES_0(0),
    DEGREES_90(90),
    DEGREES_180(180),
    DEGREES_270(270),
}

enum class MediaContainer(override val wire: String) : WireEnum {
    JPEG("jpeg"),
    PNG("png"),
    WEBP("webp"),
    HEIF("heif"),
    MP4("mp4"),
    MOV("mov"),
    WEBM("webm"),
}

internal object StripConstants {
    const val UINT32_MAX = 0xffff_ffffL
    const val UINT32_BYTES = 4
    const val UINT64_BYTES = 8
    const val TIME_FIELDS_OFFSET = 4
    const val ENTRY_COUNT_OFFSET = 4
    const val ENTRIES_OFFSET = 8
    const val VERSION_1 = 1
    const val TIME_FIELD_COUNT = 2
    const val UINT32_BITS = 32
    const val BOX_FTYP = "ftyp"
    const val BOX_MDAT = "mdat"
    const val BOX_FREE = "free"
    const val BOX_SKIP = "skip"
    const val BOX_WIDE = "wide"
    const val BOX_MOOF = "moof"
    const val BOX_UUID = "uuid"
    const val BOX_STCO = "stco"
    const val BOX_CO64 = "co64"
    val KEPT_TOP_LEVEL: Set<String> = setOf(BOX_FTYP, BOX_MOOV, BOX_MDAT, BOX_FREE, BOX_SKIP, BOX_WIDE)
    val CONTAINERS: Set<String> = setOf(BOX_MOOV, "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex")
    val REMOVED: Set<String> = setOf(BOX_UDTA, BOX_META, BOX_UUID)
    val TIMED: Set<String> = setOf(BOX_MVHD, BOX_TKHD, "mdhd")
    val METADATA_TYPES: Set<String> = REMOVED + BOX_XYZ

    const val EBML_ID_SEGMENT = 0x18538067L
    const val EBML_ID_INFO = 0x1549a966L
    const val EBML_ID_CLUSTER = 0x1f43b675L
    const val EBML_ID_DATE_UTC = 0x4461L
    const val EBML_DATE_BYTES = 8
    const val EBML_HEADER_PEEK_BYTES = 12
    const val EBML_MAX_ID_BYTES = 4
    const val EBML_MAX_SIZE_BYTES = 8
    const val BITS_PER_BYTE = 8
    const val BYTE_MASK = 0xff
}

internal object ByteConstants {
    const val BYTE_MASK = 0xff
    const val BITS_PER_BYTE = 8
}
