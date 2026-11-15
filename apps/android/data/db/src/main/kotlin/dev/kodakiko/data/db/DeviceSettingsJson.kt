package dev.kodakiko.data.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor
import dev.kodakiko.data.db.DeviceSettingsConstants as C
import dev.kodakiko.data.db.DeviceSettingsKeys as K

internal interface ValueCodec<T> {
    fun decode(element: JsonElement): T?

    fun encode(value: T): JsonElement
}

internal object JsValues {
    fun number(element: JsonElement?): Double? {
        if (element !is JsonPrimitive || element is JsonNull || element.isString) return null
        if (!JsJsonConstants.NUMBER.matches(element.content)) return null
        return element.content.toDouble().takeIf { it.isFinite() }
    }

    fun safeInteger(element: JsonElement): Long? {
        val value = number(element) ?: return null
        if (value != floor(value) || abs(value) > C.JS_MAX_SAFE_INTEGER.toDouble()) return null
        return value.toLong()
    }
}

internal object BooleanCodec : ValueCodec<Boolean> {
    override fun decode(element: JsonElement): Boolean? {
        if (element !is JsonPrimitive || element is JsonNull || element.isString) return null
        return when (element.content) {
            JsJsonConstants.LITERAL_TRUE -> true
            JsJsonConstants.LITERAL_FALSE -> false
            else -> null
        }
    }

    override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)
}

internal class LongRangeCodec(private val min: Long, private val max: Long) : ValueCodec<Long> {
    override fun decode(element: JsonElement): Long? = JsValues.safeInteger(element)?.takeIf { it in min..max }

    override fun encode(value: Long): JsonElement = JsonPrimitive(value)
}

internal class IntRangeCodec(min: Long, max: Long) : ValueCodec<Int> {
    private val range = LongRangeCodec(min, max)

    override fun decode(element: JsonElement): Int? = range.decode(element)?.toInt()

    override fun encode(value: Int): JsonElement = JsonPrimitive(value)
}

internal class AllowedIntCodec(private val allowed: List<Int>) : ValueCodec<Int> {
    override fun decode(element: JsonElement): Int? {
        val value = JsValues.number(element) ?: return null
        return allowed.firstOrNull { it.toDouble() == value }
    }

    override fun encode(value: Int): JsonElement = JsonPrimitive(value)
}

internal class EnumCodec<T : WireValue>(private val values: List<T>) : ValueCodec<T> {
    override fun decode(element: JsonElement): T? {
        if (element !is JsonPrimitive || !element.isString) return null
        return values.firstOrNull { it.wire == element.content }
    }

    override fun encode(value: T): JsonElement = JsonPrimitive(value.wire)
}

internal object StringListCodec : ValueCodec<List<String>> {
    override fun decode(element: JsonElement): List<String>? {
        if (element !is JsonArray) return null
        return element.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
    }

    override fun encode(value: List<String>): JsonElement = JsonArray(value.map(::JsonPrimitive))
}

private fun <T> JsonObject.required(key: String, codec: ValueCodec<T>): T? = get(key)?.let(codec::decode)

private val triState = EnumCodec(TriState.entries)

internal object GalleryBadgesCodec : ValueCodec<GalleryBadges> {
    override fun decode(element: JsonElement): GalleryBadges? {
        val obj = element as? JsonObject ?: return null
        return GalleryBadges(
            video = obj.required(K.VIDEO, BooleanCodec) ?: return null,
            photoCount = obj.required(K.PHOTO_COUNT, BooleanCodec) ?: return null,
            note = obj.required(K.NOTE, BooleanCodec) ?: return null,
            pending = obj.required(K.PENDING, BooleanCodec) ?: return null,
            keptOffline = obj.required(K.KEPT_OFFLINE, BooleanCodec) ?: return null,
            favorite = obj[K.FAVORITE]?.let { BooleanCodec.decode(it) ?: return null } ?: C.BADGE_FAVORITE_DEFAULT,
        )
    }

    override fun encode(value: GalleryBadges): JsonElement = JsonObject(
        linkedMapOf(
            K.VIDEO to JsonPrimitive(value.video),
            K.PHOTO_COUNT to JsonPrimitive(value.photoCount),
            K.NOTE to JsonPrimitive(value.note),
            K.PENDING to JsonPrimitive(value.pending),
            K.KEPT_OFFLINE to JsonPrimitive(value.keptOffline),
            K.FAVORITE to JsonPrimitive(value.favorite),
        ),
    )
}

internal object GallerySortCodec : ValueCodec<GallerySort> {
    private val sortKey = EnumCodec(GallerySortKey.entries)

    override fun decode(element: JsonElement): GallerySort? {
        val obj = element as? JsonObject ?: return null
        return GallerySort(
            key = obj.required(K.SORT_KEY, sortKey) ?: return null,
            descending = obj.required(K.DESCENDING, BooleanCodec) ?: return null,
        )
    }

    override fun encode(value: GallerySort): JsonElement = JsonObject(
        linkedMapOf(
            K.SORT_KEY to sortKey.encode(value.key),
            K.DESCENDING to JsonPrimitive(value.descending),
        ),
    )
}

internal object GalleryFiltersCodec : ValueCodec<GalleryFilters> {
    override fun decode(element: JsonElement): GalleryFilters? {
        val obj = element as? JsonObject ?: return null
        return GalleryFilters(
            video = obj.required(K.VIDEO, triState) ?: return null,
            note = obj.required(K.NOTE, triState) ?: return null,
            multiple = obj.required(K.MULTIPLE, triState) ?: return null,
            shared = obj.required(K.SHARED, triState) ?: return null,
            pending = obj.required(K.PENDING, triState) ?: return null,
            keptOffline = obj.required(K.KEPT_OFFLINE, triState) ?: return null,
            favorite = obj[K.FAVORITE]?.let { triState.decode(it) ?: return null } ?: C.FILTER_FAVORITE_DEFAULT,
        )
    }

    override fun encode(value: GalleryFilters): JsonElement = JsonObject(
        linkedMapOf(
            K.VIDEO to triState.encode(value.video),
            K.NOTE to triState.encode(value.note),
            K.MULTIPLE to triState.encode(value.multiple),
            K.SHARED to triState.encode(value.shared),
            K.PENDING to triState.encode(value.pending),
            K.KEPT_OFFLINE to triState.encode(value.keptOffline),
            K.FAVORITE to triState.encode(value.favorite),
        ),
    )
}

internal class SettingsField<T>(
    val name: String,
    private val codec: ValueCodec<T>,
    private val read: (DeviceSettings) -> T,
    private val write: (DeviceSettings, T) -> DeviceSettings,
) {
    fun encode(settings: DeviceSettings): JsonElement = codec.encode(read(settings))

    fun decodeInto(settings: DeviceSettings, element: JsonElement): DeviceSettings? = codec.decode(element)?.let { write(settings, it) }
}

private fun flag(name: String, read: (DeviceSettings) -> Boolean, write: (DeviceSettings, Boolean) -> DeviceSettings) = SettingsField(name, BooleanCodec, read, write)

private val columns = IntRangeCodec(C.GALLERY_COLUMNS_AUTO, C.GALLERY_COLUMNS_MAX)

object DeviceSettingsJson {
    internal val FIELDS: List<SettingsField<*>> = listOf(
        flag(K.ENCRYPT_THUMBNAILS, { it.encryptThumbnails }) { s, v -> s.copy(encryptThumbnails = v) },
        SettingsField(K.ORIGINAL_CACHE_CAP_BYTES, LongRangeCodec(C.ORIGINAL_CACHE_CAP_BYTES_MIN, C.JS_MAX_SAFE_INTEGER), { it.originalCacheCapBytes }) { s, v ->
            s.copy(originalCacheCapBytes = v)
        },
        flag(K.LARGE_UPLOADS_ON_WIFI_ONLY, { it.largeUploadsOnWifiOnly }) { s, v -> s.copy(largeUploadsOnWifiOnly = v) },
        SettingsField(K.LARGE_UPLOAD_THRESHOLD_BYTES, LongRangeCodec(C.MIN_LARGE_UPLOAD_THRESHOLD_BYTES, C.JS_MAX_SAFE_INTEGER), { it.largeUploadThresholdBytes }) { s, v ->
            s.copy(largeUploadThresholdBytes = v)
        },
        flag(K.LOCATION_FOR_CAPTURES, { it.locationForCaptures }) { s, v -> s.copy(locationForCaptures = v) },
        SettingsField(K.AUTO_LOCK_MINUTES, IntRangeCodec(C.AUTO_LOCK_MINUTES_MIN, C.AUTO_LOCK_MINUTES_MAX), { it.autoLockMinutes }) { s, v -> s.copy(autoLockMinutes = v) },
        SettingsField(K.APP_THEME, EnumCodec(AppTheme.entries), { it.appTheme }) { s, v -> s.copy(appTheme = v) },
        SettingsField(K.DATE_FORMAT, EnumCodec(DateFormat.entries), { it.dateFormat }) { s, v -> s.copy(dateFormat = v) },
        flag(K.RELATIVE_DATES, { it.relativeDates }) { s, v -> s.copy(relativeDates = v) },
        SettingsField(K.START_SCREEN, EnumCodec(StartScreen.entries), { it.startScreen }) { s, v -> s.copy(startScreen = v) },
        SettingsField(K.GALLERY_DISPLAY_MODE, EnumCodec(GalleryDisplayMode.entries), { it.galleryDisplayMode }) { s, v -> s.copy(galleryDisplayMode = v) },
        SettingsField(K.GALLERY_COLUMNS_PORTRAIT, columns, { it.galleryColumnsPortrait }) { s, v -> s.copy(galleryColumnsPortrait = v) },
        SettingsField(K.GALLERY_COLUMNS_LANDSCAPE, columns, { it.galleryColumnsLandscape }) { s, v -> s.copy(galleryColumnsLandscape = v) },
        flag(K.GALLERY_GROUP_BY_DAY, { it.galleryGroupByDay }) { s, v -> s.copy(galleryGroupByDay = v) },
        SettingsField(K.GALLERY_GROUP_BY, EnumCodec(GalleryGroup.entries), { it.galleryGroupBy }) { s, v -> s.copy(galleryGroupBy = v) },
        SettingsField(K.GALLERY_BADGES, GalleryBadgesCodec, { it.galleryBadges }) { s, v -> s.copy(galleryBadges = v) },
        flag(K.GALLERY_SHOW_TAG_TABS, { it.galleryShowTagTabs }) { s, v -> s.copy(galleryShowTagTabs = v) },
        flag(K.GALLERY_SHOW_COUNTS, { it.galleryShowCounts }) { s, v -> s.copy(galleryShowCounts = v) },
        SettingsField(K.GALLERY_SORT, GallerySortCodec, { it.gallerySort }) { s, v -> s.copy(gallerySort = v) },
        SettingsField(K.GALLERY_FILTERS, GalleryFiltersCodec, { it.galleryFilters }) { s, v -> s.copy(galleryFilters = v) },
        flag(K.HIDE_SHARED, { it.hideShared }) { s, v -> s.copy(hideShared = v) },
        flag(K.ON_THIS_DAY, { it.onThisDay }) { s, v -> s.copy(onThisDay = v) },
        flag(K.CONFIRM_TRASH, { it.confirmTrash }) { s, v -> s.copy(confirmTrash = v) },
        SettingsField(K.CAPTURE_START_CAMERA, EnumCodec(CameraSide.entries), { it.captureStartCamera }) { s, v -> s.copy(captureStartCamera = v) },
        flag(K.CAPTURE_KEEP_SCREEN_ON, { it.captureKeepScreenOn }) { s, v -> s.copy(captureKeepScreenOn = v) },
        flag(K.HIDE_NOTE_PREVIEWS, { it.hideNotePreviews }) { s, v -> s.copy(hideNotePreviews = v) },
        flag(K.SECURE_SCREEN, { it.secureScreen }) { s, v -> s.copy(secureScreen = v) },
        flag(K.FULL_SCREEN, { it.fullScreen }) { s, v -> s.copy(fullScreen = v) },
        flag(K.HIDE_BARS_ON_SCROLL, { it.hideBarsOnScroll }) { s, v -> s.copy(hideBarsOnScroll = v) },
        flag(K.SHOW_TECHNICAL_DETAILS, { it.showTechnicalDetails }) { s, v -> s.copy(showTechnicalDetails = v) },
        flag(K.HOLD_TO_RECORD_ON, { it.holdToRecordOn }) { s, v -> s.copy(holdToRecordOn = v) },
        SettingsField(K.HOLD_TO_RECORD_MAX_SECONDS, AllowedIntCodec(C.HOLD_TO_RECORD_SECONDS), { it.holdToRecordMaxSeconds }) { s, v -> s.copy(holdToRecordMaxSeconds = v) },
        SettingsField(K.CAMERA_APP, EnumCodec(CameraApp.entries), { it.cameraApp }) { s, v -> s.copy(cameraApp = v) },
        flag(K.SYNC_PAUSED, { it.syncPaused }) { s, v -> s.copy(syncPaused = v) },
        SettingsField(K.TAG_ORDER, StringListCodec, { it.tagOrder }) { s, v -> s.copy(tagOrder = v) },
        flag(K.PURE_BLACK, { it.pureBlack }) { s, v -> s.copy(pureBlack = v) },
        flag(K.HAPTIC_FEEDBACK, { it.hapticFeedback }) { s, v -> s.copy(hapticFeedback = v) },
        flag(K.DYNAMIC_COLOR, { it.dynamicColor }) { s, v -> s.copy(dynamicColor = v) },
        flag(K.AUTOPLAY_VIDEOS, { it.autoplayVideos }) { s, v -> s.copy(autoplayVideos = v) },
        flag(K.AUTOPLAY_ON_MOBILE_DATA, { it.autoplayOnMobileData }) { s, v -> s.copy(autoplayOnMobileData = v) },
        SettingsField(K.TIME_FORMAT, EnumCodec(TimeFormat.entries), { it.timeFormat }) { s, v -> s.copy(timeFormat = v) },
    )

    fun encode(value: DeviceSettings): JsonObject = JsonObject(FIELDS.associateTo(LinkedHashMap()) { it.name to it.encode(value) })

    fun encodeStored(value: DeviceSettings): JsonObject =
        JsonObject(linkedMapOf<String, JsonElement>(K.SCHEMA_VERSION to JsonPrimitive(C.SCHEMA_VERSION)) + encode(value))

    fun encodeStoredBytes(value: DeviceSettings): ByteArray = JsJson.stringifyBytes(encodeStored(value))

    fun decode(element: JsonElement): DeviceSettings {
        require(element is JsonObject) { DbText.deviceSettingsNotObject() }
        val invalid = mutableListOf<String>()
        if (!hasCurrentSchemaVersion(element)) invalid += K.SCHEMA_VERSION
        var value = DeviceSettingsDefaults.ANDROID
        for (field in FIELDS) {
            value = element[field.name]?.let { field.decodeInto(value, it) } ?: run {
                invalid += field.name
                value
            }
        }
        require(invalid.isEmpty()) { DbText.invalidDeviceSettings(invalid) }
        return value
    }

    fun resolve(raw: JsonElement?, defaults: DeviceSettings): ResolvedDeviceSettings {
        if (raw !is JsonObject) return ResolvedDeviceSettings(defaults, emptyList())
        if (!hasCurrentSchemaVersion(raw)) return ResolvedDeviceSettings(defaults, listOf(K.SCHEMA_VERSION))
        val invalid = mutableListOf<String>()
        var value = defaults
        for (field in FIELDS) {
            val element = raw[field.name] ?: continue
            value = field.decodeInto(value, element) ?: run {
                invalid += field.name
                value
            }
        }
        return ResolvedDeviceSettings(value, invalid)
    }

    private fun hasCurrentSchemaVersion(raw: JsonObject): Boolean = JsValues.number(raw[K.SCHEMA_VERSION]) == C.SCHEMA_VERSION.toDouble()
}

data class ResolvedDeviceSettings(val value: DeviceSettings, val invalid: List<String>)
