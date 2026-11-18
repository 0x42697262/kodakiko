package dev.kodakiko.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.account.KdfParams
import dev.kodakiko.account.deriveLoginMaterial
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.openPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.unwrapKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class LegacyDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val serverHash = "72a22fb39f638ca4"
    private val localHash = "b1abbb91a5439ec1"

    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }

    private fun fixture(account: String) = File(repoRoot, "tests/fixtures/v031-device/$account")

    private fun install(account: String, hash: String): File {
        val directory = File(context.filesDir, "SQLite").apply { mkdirs() }
        for (suffix in listOf("", "-wal", "-shm")) {
            File(fixture(account), "files/SQLite/kodakiko-$hash.db$suffix").copyTo(File(directory, "kodakiko-$hash.db$suffix"), overwrite = true)
        }
        return File(directory, "kodakiko-$hash.db")
    }

    private fun referenceSchema(account: String, hash: String): List<String> {
        val copy = File(context.cacheDir, "reference-$account").apply {
            deleteRecursively()
            mkdirs()
        }
        for (suffix in listOf("", "-wal", "-shm")) {
            File(fixture(account), "files/SQLite/kodakiko-$hash.db$suffix").copyTo(File(copy, "r.db$suffix"))
        }
        return schemaOf(File(copy, "r.db"))
    }

    private fun schemaOf(file: File): List<String> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT type || ' ' || name || ' ' || coalesce(sql, '') FROM sqlite_master WHERE name != 'android_metadata' AND name NOT LIKE 'sqlite_%' ORDER BY type, name",
                null,
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        }

    private fun pragma(file: File, sql: String): String =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
        }

    private fun headerJournalBytes(file: File): Pair<Int, Int> {
        val header = file.inputStream().use { it.readNBytes(20) }
        return (header[18].toInt() and 0xff) to (header[19].toInt() and 0xff)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun serverFixtureOpensInPlaceWithEveryRow() {
        val reference = referenceSchema("server", serverHash)
        val file = install("server", serverHash)
        val database = KodakikoDatabase.open(context, serverHash)
        try {
            database.transaction { tx ->
                assertEquals(1, tx.collections.all().size)
                val home = tx.collections.all().single()
                assertEquals(CollectionKind.HOME, home.kind)
                assertEquals(CollectionRole.OWNER, home.role)
                val entries = tx.entries.all()
                assertEquals(8, entries.size)
                assertEquals(1, entries.count { it.trashedAt != null })
                assertEquals(7, tx.entries.byLocalState(listOf(EntryLocalState.SYNCED)).size)
                assertEquals(1, tx.entries.byLocalState(listOf(EntryLocalState.PENDING_UPDATE)).size)
                assertEquals(8, tx.memberships.all().size)
                assertEquals(8, tx.memberships.byCollection(home.id).size)
                assertEquals(12, tx.media.all().size)
                assertEquals(24, tx.objects.all().size)
                assertEquals(listOf("rYnbxnkqoQDUwhF2rywyKA"), tx.pins.all().map { it.entryId })
                val outbox = tx.outbox.all().single()
                assertEquals(1L, outbox.id)
                assertEquals("updateEntryMeta", outbox.kind)
                assertEquals(OutboxState.QUEUED, outbox.state)
                assertEquals(1L, outbox.attempts)
                assertEquals("NETWORK", outbox.lastErrorCode)
                assertEquals(JsonObject(mapOf("entryId" to JsonPrimitive("ZKM4YN624mDXnrUUVdXSfg"))), outbox.payload)
                assertEquals(mapOf(OutboxState.QUEUED to 1L, OutboxState.DEAD to 0L), tx.outbox.countByState())
                val presentOriginals = tx.objects.presentOriginalsByAccess()
                assertEquals(1, presentOriginals.size)
                assertTrue(presentOriginals.all { it.variant == ObjectVariant.ORIGINAL && it.localState == ObjectLocalState.PRESENT })
            }
            assertEquals(JsonPrimitive("pufepQc0df0nbPs1EifhgA"), database.kvGetJson(StorageKeys.ACCOUNT_USER_ID))
            assertEquals(JsonPrimitive("member"), database.kvGetJson(StorageKeys.ACCOUNT_ROLE))
            assertEquals(73, database.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK)!!.size)
            assertNull(database.kvGet(StorageKeys.SETTINGS_DEVICE))
            assertEquals("wal", database.pragma("journal_mode"))
            assertEquals("1", database.pragma("foreign_keys"))
            assertEquals("1", database.pragma("user_version"))
        } finally {
            database.close()
        }
        assertEquals(2 to 2, headerJournalBytes(file))
        assertEquals("ok", pragma(file, "PRAGMA integrity_check"))
        assertEquals("1", pragma(file, "PRAGMA user_version"))
        assertEquals(reference, schemaOf(file))
    }

    @Test
    fun localFixtureOpensWithItsLocalModeKeys() {
        install("local", localHash)
        val database = KodakikoDatabase.open(context, localHash)
        try {
            database.transaction { tx ->
                val entry = tx.entries.all().single()
                assertEquals(EntryLocalState.PENDING_CREATE, entry.localState)
                assertEquals("createEntry", tx.outbox.all().single().kind)
                assertTrue(tx.objects.all().all { it.uploadState == ObjectUploadState.LOCAL })
            }
            assertEquals(JsonPrimitive("local"), database.kvGetJson(StorageKeys.ACCOUNT_MODE))
            assertEquals(73, database.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_RECOVERY)!!.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun theFixtureJournalDecryptsWithItsPassword() {
        install("server", serverHash)
        val account = Json.parseToJsonElement(File(fixture("server"), "account.json").readText()).jsonObject
        val sodium = createSodium()
        val database = KodakikoDatabase.open(context, serverHash)
        try {
            val userId = database.kvGetJson(StorageKeys.ACCOUNT_USER_ID)!!.jsonPrimitive.content
            val kdf = database.kvGetJson(StorageKeys.ACCOUNT_KDF_PARAMS)!!.jsonObject
            val params = KdfParams(
                decodeBytes(kdf.getValue("salt").jsonPrimitive.content),
                kdf.getValue("opsLimit").jsonPrimitive.long,
                kdf.getValue("memLimitBytes").jsonPrimitive.long,
            )
            val login = deriveLoginMaterial(sodium, account.getValue("PASSWORD").jsonPrimitive.content, params)
            val masterKey = unwrapKey(sodium, login.kek, database.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK)!!, AdContext.MASTER_KEY_BY_KEK, userId)
            val opened = database.transaction { tx ->
                val home = tx.collections.all().single()
                val collectionKey = unwrapKey(sodium, masterKey, home.keyMaterial, AdContext.COLLECTION_KEY_BY_MASTER, home.id)
                tx.entries.all().map { entry ->
                    val membership = tx.memberships.byEntry(entry.id).single()
                    val entryKey = unwrapKey(sodium, collectionKey, membership.entryKeyWrap, AdContext.ENTRY_KEY_BY_COLLECTION, "${home.id}/${entry.id}")
                    val metadata = openPayload(sodium, entryKey, entry.metadataBlob, ContractSchemas.entryMetadataV1, 1, emptyMap(), AdContext.ENTRY_METADATA, entry.id)
                    for (media in tx.media.byEntry(entry.id)) {
                        assertEquals(32, unwrapKey(sodium, entryKey, media.mediaKeyWrap, AdContext.MEDIA_KEY_BY_ENTRY, "${entry.id}/${media.id}").size)
                    }
                    metadata
                }
            }
            assertEquals(8, opened.size)
            assertTrue(opened.any { it.tags.contains("beach") })
        } finally {
            database.close()
        }
    }

    @Test
    fun aNewerSchemaIsRefusedWithoutTouchingTheFile() {
        val file = install("server", serverHash)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("PRAGMA user_version = 2") }
        val before = sha256(file)
        assertThrows(NewerSchemaError::class.java) { KodakikoDatabase.open(context, serverHash) }
        assertEquals(before, sha256(file))
    }

    @Test
    fun aFreshDatabaseHasTheV031Schema() {
        val reference = referenceSchema("server", serverHash)
        val hash = "0000000000000000"
        val database = KodakikoDatabase.open(context, hash)
        database.close()
        val file = File(context.filesDir, "SQLite/kodakiko-$hash.db")
        assertEquals("1", pragma(file, "PRAGMA user_version"))
        assertEquals(reference, schemaOf(file))
    }

    @Test
    fun rowsRoundTripAndQueriesMatchTheTypeScriptRowStore() {
        val database = KodakikoDatabase.open(context, "1111111111111111")
        try {
            val blob = byteArrayOf(1, 2, 3)
            database.transaction { tx ->
                tx.collections.put(CollectionRow("c1", CollectionKind.HOME, "u1", "alice", CollectionRole.OWNER, blob, blob, "2026-01-01T00:00:00.000Z", 3, 2))
                tx.entries.put(EntryRow("e1", "u1", EntryPurpose.JOURNAL, 1, blob, null, null, null, null, null, "t0", "t1", EntryLocalState.PENDING_CREATE))
                tx.entries.put(EntryRow("e2", "u1", EntryPurpose.JOURNAL, 4, blob, blob, 3, blob, 5, "t2", "t0", "t1", EntryLocalState.SYNCED))
                tx.memberships.put(MembershipRow("c1", "e1", blob, 7))
                tx.media.put(MediaRow("m1", "e1", blob))
                tx.objects.put(ObjectRow("o1", "m1", ObjectVariant.ORIGINAL, 100, 60, ObjectLocalState.PRESENT, ObjectUploadState.UPLOADED, null, 50))
                tx.objects.put(ObjectRow("o0", "m1", ObjectVariant.ORIGINAL, 100, 60, ObjectLocalState.PRESENT, ObjectUploadState.UPLOADED, "job", 50))
                tx.objects.put(ObjectRow("o2", "m1", ObjectVariant.ORIGINAL, 100, 60, ObjectLocalState.PRESENT, ObjectUploadState.LOCAL, null, 10))
                tx.objects.put(ObjectRow("o3", "m1", ObjectVariant.THUMBNAIL, 40, 20, ObjectLocalState.PRESENT, ObjectUploadState.UPLOADED, null, 1))
                tx.pins.put(PinRow("e1", 9))
            }
            database.transaction { tx ->
                val e2 = tx.entries.get("e2")!!
                assertArrayEquals(blob, e2.baseMetadataBlob)
                assertEquals(5L, e2.serverPendingRevision)
                assertEquals("t2", e2.trashedAt)
                assertNull(tx.entries.get("e1")!!.baseRevision)
                assertEquals(listOf("e1"), tx.entries.byLocalState(listOf(EntryLocalState.PENDING_CREATE, EntryLocalState.PENDING_UPDATE)).map { it.id })
                assertEquals(listOf("e1"), tx.memberships.byCollection("c1").map { it.entryId })
                assertEquals(7L, tx.memberships.get("c1", "e1")!!.seq)
                assertEquals(listOf("o2", "o0", "o1"), tx.objects.presentOriginalsByAccess().map { it.id })
                assertEquals(4, tx.objects.byMedia("m1").size)
                tx.memberships.delete("c1", "e1")
                assertNull(tx.memberships.get("c1", "e1"))
                tx.entries.put(tx.entries.get("e1")!!.copy(localState = EntryLocalState.SYNCED, revision = 2))
                assertEquals(2L, tx.entries.get("e1")!!.revision)
            }
            val first = database.transaction { tx -> tx.outbox.enqueue(NewOutboxRow("createEntry", JsonObject(mapOf("entryId" to JsonPrimitive("e1"))), OutboxState.QUEUED, 1, 0, 100, null)) }
            val second = database.transaction { tx -> tx.outbox.enqueue(NewOutboxRow("trashEntry", JsonObject(mapOf("entryId" to JsonPrimitive("e2"))), OutboxState.QUEUED, 2, 0, 50, null)) }
            val third = database.transaction { tx -> tx.outbox.enqueue(NewOutboxRow("addMedia", JsonObject(mapOf("entryId" to JsonPrimitive("e2"))), OutboxState.DEAD, 3, 8, 0, "QUOTA_EXCEEDED")) }
            assertTrue(first < second && second < third)
            database.transaction { tx ->
                assertEquals(listOf(second, first), tx.outbox.due(100, 10).map { it.id })
                assertEquals(listOf(second), tx.outbox.due(100, 1).map { it.id })
                assertEquals(emptyList<Long>(), tx.outbox.due(49, 10).map { it.id })
                assertEquals(mapOf(OutboxState.QUEUED to 2L, OutboxState.DEAD to 1L), tx.outbox.countByState())
                tx.outbox.update(tx.outbox.all().first { it.id == first }.copy(attempts = 4, lastErrorCode = "NETWORK"))
                assertEquals(4L, tx.outbox.all().first { it.id == first }.attempts)
                tx.outbox.delete(third)
                assertEquals(listOf(first, second), tx.outbox.all().map { it.id })
            }
            database.kvSet(StorageKeys.ACCOUNT_PUBLIC_KEY, blob)
            assertArrayEquals(blob, database.kvGet(StorageKeys.ACCOUNT_PUBLIC_KEY))
            database.kvSetJson(StorageKeys.ACCOUNT_ROLE, JsonPrimitive("admin"))
            assertEquals(JsonPrimitive("admin"), database.kvGetJson(StorageKeys.ACCOUNT_ROLE))
            assertEquals("\"admin\"", String(database.kvGet(StorageKeys.ACCOUNT_ROLE)!!))
            database.kvDelete(StorageKeys.ACCOUNT_ROLE)
            assertNull(database.kvGet(StorageKeys.ACCOUNT_ROLE))
        } finally {
            database.close()
        }
    }

    @Test
    fun aFailedTransactionChangesNothing() {
        val database = KodakikoDatabase.open(context, "2222222222222222")
        try {
            assertThrows(IllegalStateException::class.java) {
                database.transaction { tx ->
                    tx.pins.put(PinRow("e1", 1))
                    error("boom")
                }
            }
            assertNotNull(database.transaction { tx -> tx.pins.all() })
            assertEquals(0, database.transaction { tx -> tx.pins.all().size })
        } finally {
            database.close()
        }
    }

    @Test
    fun storageKeysMatchTheTypeScriptNames() {
        assertEquals(
            listOf(
                "account.userId", "account.username", "account.kdfParams", "account.masterKeyByKek", "account.masterKeyByRecovery",
                "account.mode", "account.recoveryKeyByMaster", "account.publicKey", "account.privateKeyByMaster",
                "account.recoveryConfirmed", "account.homeCollectionId", "account.role", "account.deletionDueAt",
                "device.storagePersisted", "device.notificationPermissionAsked", "settings.device", "diagnostics.probe",
            ),
            StorageKeys.ALL,
        )
    }
}
