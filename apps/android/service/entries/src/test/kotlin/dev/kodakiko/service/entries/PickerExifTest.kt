package dev.kodakiko.service.entries

import dev.kodakiko.media.ImageMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickerExifTest {
    @Test
    fun returnsEmptyFieldsForAnEmptyObject() {
        assertEquals(
            ImageMetadata(dateTimeOriginal = null, offsetTimeOriginal = null, gps = null, orientation = null, malformed = false),
            pickerExifToMetadata(JsonObject(emptyMap())),
        )
    }

    @Test
    fun readsDateOffsetAndOrientation() {
        val result = pickerExifToMetadata(
            buildJsonObject {
                put("DateTimeOriginal", "2026:05:01 10:00:00")
                put("OffsetTimeOriginal", "+02:00")
                put("Orientation", 6)
            },
        )
        assertEquals("2026:05:01 10:00:00", result.dateTimeOriginal)
        assertEquals("+02:00", result.offsetTimeOriginal)
        assertEquals(6, result.orientation)
    }

    @Test
    fun acceptsGpsAsDecimalNumbersWithReferenceLetters() {
        val result = pickerExifToMetadata(
            buildJsonObject {
                put("GPSLatitude", 48.8584)
                put("GPSLatitudeRef", "N")
                put("GPSLongitude", 2.2945)
                put("GPSLongitudeRef", "E")
            },
        )
        assertEquals(48.8584, result.gps?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(2.2945, result.gps?.longitude ?: 0.0, COORDINATE_DELTA)
    }

    @Test
    fun negatesSouthernAndWesternNumbers() {
        val result = pickerExifToMetadata(
            buildJsonObject {
                put("GPSLatitude", 33.8688)
                put("GPSLatitudeRef", "S")
                put("GPSLongitude", 70.6693)
                put("GPSLongitudeRef", "W")
            },
        )
        assertEquals(-33.8688, result.gps?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(-70.6693, result.gps?.longitude ?: 0.0, COORDINATE_DELTA)
    }

    @Test
    fun acceptsGpsAsDegreeMinuteSecondFractionStrings() {
        val result = pickerExifToMetadata(
            buildJsonObject {
                put("GPSLatitude", "48/1,51/1,3024/100")
                put("GPSLatitudeRef", "N")
                put("GPSLongitude", "2/1,17/1,4020/100")
                put("GPSLongitudeRef", "E")
            },
        )
        assertEquals(48.8584, result.gps?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(2.2945, result.gps?.longitude ?: 0.0, COORDINATE_DELTA)
    }

    @Test
    fun negatesSouthernAndWesternFractionStrings() {
        val result = pickerExifToMetadata(
            buildJsonObject {
                put("GPSLatitude", "33/1,52/1,768/100")
                put("GPSLatitudeRef", "S")
                put("GPSLongitude", "70/1,40/1,948/100")
                put("GPSLongitudeRef", "W")
            },
        )
        assertEquals(-33.8688, result.gps?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(-70.6693, result.gps?.longitude ?: 0.0, COORDINATE_DELTA)
    }

    @Test
    fun returnsNullGpsForValuesItCannotRead() {
        assertNull(
            pickerExifToMetadata(
                buildJsonObject {
                    put("GPSLatitude", "north-ish")
                    put("GPSLatitudeRef", "N")
                    put("GPSLongitude", "2/1,17/1,4020/100")
                    put("GPSLongitudeRef", "E")
                },
            ).gps,
        )
        assertNull(
            pickerExifToMetadata(
                buildJsonObject {
                    put("GPSLatitude", 48.8584)
                    put("GPSLatitudeRef", "N")
                },
            ).gps,
        )
        assertNull(
            pickerExifToMetadata(
                buildJsonObject {
                    put("GPSLatitude", JsonObject(emptyMap()))
                    put("GPSLatitudeRef", "N")
                    put("GPSLongitude", JsonArray(emptyList()))
                    put("GPSLongitudeRef", "E")
                },
            ).gps,
        )
    }

    @Test
    fun returnsNullForDateOffsetAndOrientationOfTheWrongShape() {
        val result = pickerExifToMetadata(
            buildJsonObject {
                put("DateTimeOriginal", "yesterday")
                put("OffsetTimeOriginal", "later")
                put("Orientation", "sideways")
            },
        )
        assertNull(result.dateTimeOriginal)
        assertNull(result.offsetTimeOriginal)
        assertNull(result.orientation)
    }

    @Test
    fun returnsNullForValuesThatAreNotStringsOrNumbersAtAll() {
        val result = pickerExifToMetadata(
            buildJsonObject {
                put("DateTimeOriginal", 20260501)
                put("OffsetTimeOriginal", JsonNull)
                put("Orientation", buildJsonObject { put("value", 6) })
            },
        )
        assertNull(result.dateTimeOriginal)
        assertNull(result.offsetTimeOriginal)
        assertNull(result.orientation)
    }
}
