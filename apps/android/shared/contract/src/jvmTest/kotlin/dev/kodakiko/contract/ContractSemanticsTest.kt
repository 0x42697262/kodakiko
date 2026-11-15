package dev.kodakiko.contract

import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.MalformedPayloadError
import dev.kodakiko.crypto.PADDING_BLOCK_BYTES
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.padIso7816
import dev.kodakiko.crypto.sealBlob
import dev.kodakiko.crypto.utf8Encode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContractSemanticsTest {
    private fun parse(text: String) = Json.parseToJsonElement(text)

    private fun roundTrip(schema: String, input: String): String {
        val contract = ContractSchemas.all.getValue(schema)
        return ContractJson.stringify(contract.encode(contract.decode(parse(input))))
    }

    private fun rejects(schema: String, input: String, contract: ContractSchema<*> = ContractSchemas.all.getValue(schema)): Boolean =
        try {
            contract.decode(parse(input))
            false
        } catch (error: ContractViolation) {
            true
        }

    @Test
    fun numbersPrintLikeJavaScript() {
        val cases = listOf(
            0.0 to "0", -0.0 to "0", 5.0 to "5", -5.0 to "-5", 1.5 to "1.5", 0.1 to "0.1",
            0.000001 to "0.000001", 0.0000001 to "1e-7", 1e21 to "1e+21", 1e20 to "100000000000000000000",
            123456789012345680000.0 to "123456789012345680000", 52.370216 to "52.370216", -33.8688 to "-33.8688",
            1.0 / 3 to "0.3333333333333333", 2.0 / 3 to "0.6666666666666666", 5e-324 to "5e-324",
            1.7976931348623157e308 to "1.7976931348623157e+308", 9007199254740993.0 to "9007199254740992",
            1.234e-10 to "1.234e-10", 4294967296.0 to "4294967296", 1e300 to "1e+300",
            0.1 + 0.2 to "0.30000000000000004", 2e-3 to "0.002", 1.0e23 to "1e+23", 123e-20 to "1.23e-18",
            Double.NaN to "null", Double.POSITIVE_INFINITY to "null",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, ContractJson.stringify(JsonPrimitive(value)), value.toString())
        }
        assertEquals("[1,true,null,\"\\ud800x\"]", ContractJson.stringify(JsonArray(listOf(JsonPrimitive(1L), JsonPrimitive(true), JsonNull, JsonPrimitive("\ud800x")))))
        assertEquals("{\"\\u001f\":{}}", ContractJson.stringify(JsonObject(mapOf("\u001f" to JsonObject(emptyMap())))))
    }

    @Test
    fun queryNumbersCoerceLikeJavaScriptNumber() {
        val cases = listOf(
            "\"0x10\"" to 16.0, "\"0X1f\"" to 31.0, "\"0o17\"" to 15.0, "\"0b101\"" to 5.0, "\"-0x10\"" to Double.NaN,
            "\"+5\"" to 5.0, "\"5.\"" to 5.0, "\".5\"" to 0.5, "\".\"" to Double.NaN, "\"1E+3\"" to 1000.0, "\"1e\"" to Double.NaN,
            "\"Infinity\"" to Double.POSITIVE_INFINITY, "\"-Infinity\"" to Double.NEGATIVE_INFINITY, "\"infinity\"" to Double.NaN,
            "\"NaN\"" to Double.NaN, "\"1_000\"" to Double.NaN, "\"\\u00a0 42 \\u3000\"" to 42.0, "\"\\ufeff7\"" to 7.0,
            "\"\\u001c7\"" to Double.NaN, "\"12abc\"" to Double.NaN, "\"0012\"" to 12.0, "\"1f\"" to Double.NaN, "\"1d\"" to Double.NaN,
            "\"0x\"" to Double.NaN, "\" \\n\\t3\\r \"" to 3.0, "\"\"" to 0.0, "null" to 0.0, "true" to 1.0, "false" to 0.0,
            "[]" to 0.0, "[5]" to 5.0, "[\"5\"]" to 5.0, "[[1]]" to 1.0, "[1,2]" to Double.NaN, "[null]" to 0.0,
            "[true]" to Double.NaN, "{}" to Double.NaN, "[\"  9 \"]" to 9.0,
        )
        for ((input, expected) in cases) {
            assertEquals(expected, jsToNumber(parse(input)), input)
        }
        assertEquals("{\"since\":16,\"limit\":10}", roundTrip("diffQuery", "{\"since\":\" 0x10 \",\"limit\":\"1e1\"}"))
        assertEquals("{\"since\":0,\"limit\":5}", roundTrip("diffQuery", "{\"since\":null,\"limit\":[5]}"))
        assertTrue(rejects("diffQuery", "{\"since\":\"Infinity\"}"))
    }

    @Test
    fun urlsParseLikeTheWhatwgParser() {
        val cases = mapOf(
            "a:" to true, "a: b" to true, "http:example.com" to true, "https://" to false, "https://a b" to false,
            "https://a%20b" to false, "https://[::1]/x" to true, "https://[zz]/" to false, "https://1.2.3.256/" to false,
            "https://1.2.3.4.5/" to false, "https://0x7f.1/" to true, "https://a.b:65536/" to false, "https://a.b:65535/" to true,
            "https://a.b:x/" to false, "https://user:pw@host/" to true, "https://@/" to false, "https://\u00fc.com/" to true,
            "file:" to true, "file:///x" to true, "ftp://" to false, "a://" to true, "a://b c/" to false, "a://[x" to false,
            "1a:b" to false, "a+b.c-d:x" to true, " \u0001https://a.b\u0000 " to true, "https://a.b/\u00e9 x" to true,
            "https://a<b/" to false, "https://a^b/" to false, "https://a|b/" to false, "https:\\\\a.b" to true,
            "https:///a.b" to true, "https://a..b/" to true, "https://." to true, "https://a.b#\u0000" to true, "ws:x" to true,
            "not a url" to false, "https://a.123" to false, "https://a.1x" to true, "https://%41.com" to true,
            "https://%zz.com" to false, "mailto:" to true, "a:\u0000" to true, "a://..@" to false, "http://\u200b" to false,
            "https://a\u00adb/" to true, "https://a\u200cb/" to false, "https://xn--nxasmq6b/" to true, "https://xn--a/" to false,
            "https://xn--abc-/" to false, "https://\uff0f/" to false, "http://xn--nxasmq6b\uff0e\u00e9" to true,
            "http://\u200b4294967296" to false, "https://\u0080/" to false,
        )
        for ((input, expected) in cases) {
            assertEquals(expected, canParseUrl(input), input)
        }
        assertEquals(
            "{\"url\":\"https://a.b/xy\",\"expiresAt\":\"2026-02-01T00:00:00Z\"}",
            roundTrip("downloadResponse", "{\"url\":\" https://a.b/x\\ty \",\"expiresAt\":\"2026-02-01T00:00:00Z\"}"),
        )
    }

    @Test
    fun zodQuirksAreMirrored() {
        val times = mapOf(
            "2026-02-01T00:00:00.1Z" to true, "2026-02-01T00:00Z" to false, "2026-02-29T00:00:00Z" to false,
            "2028-02-29T00:00:00Z" to true, "2000-02-29T00:00:00Z" to true, "1900-02-29T00:00:00Z" to false,
            "2026-02-01T24:00:00Z" to false, "2026-02-01T00:00:00z" to false, "2026-02-01T00:00:00.Z" to false,
            "2026-02-01T00:00:00Z\n" to false, "2026-02-01T00:00:00+01:00" to false,
        )
        for ((time, valid) in times) {
            assertEquals(!valid, rejects("deletionRequestResponse", JsonObject(mapOf("deletionDueAt" to JsonPrimitive(time))).toString()), time)
        }
        val wave = "\ud83c\udf0a"
        assertTrue(!rejects("TagName", "\"${wave.repeat(TAG_MAX_CHARS)}\""))
        assertTrue(rejects("TagName", "\"${wave.repeat(TAG_MAX_CHARS + 1)}\""))
        assertTrue(!rejects("CollectionMetadataV1", "{\"schemaVersion\":1.0,\"name\":\"$wave\",\"createdAtUtc\":\"2026-02-01T00:00:00Z\"}"))
        assertTrue(rejects("errorResponseSchema", "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"m\",\"requestId\":\"r\"}}"))
        assertTrue(rejects("linkDownload", "{\"kind\":1,\"path\":\"/x\"}"))
        assertTrue(rejects("presignRequest", "{\"id\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"mediaId\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"variant\":\"original\",\"sizeBytes\":9007199254740992}"))
        assertEquals("{\"quotaBytes\":null}", roundTrip("adminUserPatchRequest", "{\"quotaBytes\":null,\"extra\":1}"))
        assertEquals(
            "{\"id\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"mediaId\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"variant\":\"original\",\"sizeBytes\":40,\"state\":\"pending\",\"download\":{\"kind\":\"proxy\",\"path\":\"/x\"}}",
            roundTrip(
                "linkObjectDto",
                "{\"download\":{\"path\":\"/x\",\"kind\":\"proxy\"},\"state\":\"pending\",\"sizeBytes\":40.0,\"variant\":\"original\",\"mediaId\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"id\":\"AAAAAAAAAAAAAAAAAAAAAA\"}",
            ),
        )
        assertTrue(
            roundTrip(
                "collectionDto",
                "{\"id\":\"AwQFBgcICQoLDA0ODxAREg\",\"kind\":\"home\",\"role\":\"owner\",\"ownerId\":\"AQIDBAUGBwgJCgsMDQ4PEA\",\"ownerUsername\":\"alice\",\"keyMaterial\":\"AA\",\"metadataBlob\":\"AA\",\"lastSeq\":0,\"createdAt\":\"2026-02-01T00:00:00Z\",\"metaUpdatedAt\":\"2026-02-01T00:00:00Z\"}",
            ).endsWith(",\"recipientCount\":null}"),
        )
    }

    @Test
    fun openPayloadRejectsTextThatJavaScriptCannotParse() {
        val sodium = createSodium()
        val key = ByteArray(32) { 7 }
        val bindingId = "AAAAAAAAAAAAAAAAAAAAAA"
        for (text in listOf("{\"schemaVersion\":1,\"name\":abc}", "[1]", "{\"schemaVersion\":\"1\"}", "{\"schemaVersion\":1.5}", "nope")) {
            val blob = sealBlob(sodium, key, padIso7816(utf8Encode(text), PADDING_BLOCK_BYTES), AdContext.COLLECTION_METADATA, bindingId)
            assertFailsWith<MalformedPayloadError>(text) {
                openPayload(sodium, key, blob, ContractSchemas.collectionMetadataV1, COLLECTION_METADATA_SCHEMA_VERSION, emptyMap(), AdContext.COLLECTION_METADATA, bindingId)
            }
        }
    }

    @Test
    fun untypedRegistrySchemasOpenAndEncodeWithoutCasts() {
        val sodium = createSodium()
        val key = ByteArray(32) { 9 }
        val bindingId = "AAAAAAAAAAAAAAAAAAAAAA"
        val metadata = CollectionMetadataV1("Home", "2026-02-01T00:00:00.000Z")
        val blob = sealPayload(sodium, key, metadata, ContractSchemas.collectionMetadataV1, AdContext.COLLECTION_METADATA, bindingId)
        val schema = ContractSchemas.all.getValue("CollectionMetadataV1")
        val opened = openPayload(sodium, key, blob, schema, COLLECTION_METADATA_SCHEMA_VERSION, emptyMap(), AdContext.COLLECTION_METADATA, bindingId)
        assertEquals(metadata, opened)
        assertEquals("{\"schemaVersion\":1,\"name\":\"Home\",\"createdAtUtc\":\"2026-02-01T00:00:00.000Z\"}", ContractJson.stringify(schema.encode(opened) as JsonObject))
    }

    @Test
    fun routesAndOutboxMatchTheTypeScriptTables() {
        assertEquals("/api/v1/collections/a/shares/b", Routes.collectionShare("a", "b"))
        assertEquals("/api/v1/public-links/:linkId/objects/:objectId/content", RoutePatterns.PUBLIC_LINK_OBJECT_CONTENT)
        assertEquals(44, RoutePatterns.all.size)
        assertEquals(26, ErrorCode.entries.size)
        assertEquals(OutboxKind.entries.toSet(), entryOutboxPayloadSchemas.keys)
        assertEquals(OutboxPayloadSchemas.entryMedia, entryOutboxPayloadSchemas.getValue(OutboxKind.ADD_MEDIA))
        assertEquals(OutboxPayloadSchemas.entry, entryOutboxPayloadSchemas.getValue(OutboxKind.TRASH_ENTRY))
        val id = "AAAAAAAAAAAAAAAAAAAAAA"
        assertEquals("{\"entryId\":\"$id\",\"mediaId\":\"$id\"}", ContractJson.stringify(OutboxPayloadSchemas.entryMedia.encode(EntryMediaOutboxPayload(id, id))))
        assertTrue(rejects("addMedia", "{\"entryId\":\"$id\"}", entryOutboxPayloadSchemas.getValue(OutboxKind.ADD_MEDIA)))
    }
}
