package dev.kodakiko.data.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.account.KdfParams
import dev.kodakiko.account.deriveLoginMaterial
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.LegacyRead
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class LegacyAdoptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sodium = createSodium()
    private val serverHash = "72a22fb39f638ca4"
    private val localHash = "b1abbb91a5439ec1"
    private val serverAccountKey = "10.0.2.2:9580/pufepQc0df0nbPs1EifhgA"
    private val localAccountKey = "local/AJodCY5u6JCoGljpGC5X5w"
    private val session = "kdk_s_GhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk"

    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }

    private fun fixture(account: String) = File(repoRoot, "tests/fixtures/v031-device/$account")

    private fun install(account: String, hash: String) {
        val sqlite = File(context.filesDir, "SQLite").apply { mkdirs() }
        for (suffix in listOf("", "-wal", "-shm")) {
            File(fixture(account), "files/SQLite/kodakiko-$hash.db$suffix").copyTo(File(sqlite, "kodakiko-$hash.db$suffix"), overwrite = true)
        }
        File(fixture(account), "files/kodakiko").copyRecursively(File(context.filesDir, "kodakiko"), overwrite = true)
    }

    private fun fixtureMasterKey(): ByteArray {
        val password = Json.parseToJsonElement(File(fixture("server"), "account.json").readText()).jsonObject.getValue("PASSWORD").jsonPrimitive.content
        val database = KodakikoDatabase.open(context, serverHash)
        try {
            val kdf = database.kvGetJson(StorageKeys.ACCOUNT_KDF_PARAMS)!!.jsonObject
            val params = KdfParams(decodeBytes(kdf.getValue("salt").jsonPrimitive.content), kdf.getValue("opsLimit").jsonPrimitive.long, kdf.getValue("memLimitBytes").jsonPrimitive.long)
            val login = deriveLoginMaterial(sodium, password, params)
            val userId = database.kvGetJson(StorageKeys.ACCOUNT_USER_ID)!!.jsonPrimitive.content
            return unwrapKey(sodium, login.kek, database.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK)!!, AdContext.MASTER_KEY_BY_KEK, userId)
        } finally {
            database.close()
        }
    }

    private class FakeLegacy(val items: MutableMap<String, LegacyRead>) : LegacySecrets {
        var forgotten = 0

        override fun read(name: String): LegacyRead = items[name] ?: LegacyRead.Missing

        override fun forgetAll() {
            forgotten++
            items.clear()
        }
    }

    private class FakeTarget(private val failAt: String? = null) : AdoptionTarget {
        var serverUrl: String? = null
        var account: ActiveAccount? = null
        var config: String? = null
        val sessions = mutableMapOf<String, String>()
        val masterKeys = mutableMapOf<String, ByteArray>()
        var calls = 0

        private fun step(name: String) {
            calls++
            if (name == failAt) error("crash during $name")
        }

        override fun saveServerUrl(url: String) {
            step("serverUrl")
            serverUrl = url
        }

        override fun saveActiveAccount(account: ActiveAccount) {
            step("activeAccount")
            this.account = account
        }

        override fun saveServerConfigJson(json: String) {
            step("serverConfig")
            config = json
        }

        override fun saveSession(accountHash: String, token: String) {
            step("session")
            sessions[accountHash] = token
        }

        override fun saveMasterKey(accountHash: String, masterKey: ByteArray): Boolean {
            step("masterKey")
            masterKeys[accountHash] = masterKey.copyOf()
            return true
        }
    }

    private fun serverLegacy(masterKey: ByteArray, authMode: String = "device", masterKeyRead: LegacyRead? = null) = FakeLegacy(
        mutableMapOf(
            "kodakiko.server" to LegacyRead.Value("http://10.0.2.2:9580"),
            "kodakiko.activeAccount" to LegacyRead.Value("{\"schemaVersion\":2,\"accountKey\":\"$serverAccountKey\",\"username\":\"k007server\",\"mode\":\"server\"}"),
            "kodakiko.serverConfig" to LegacyRead.Value("{\"apiVersion\":1,\"signupMode\":\"invite\"}"),
            "kodakiko.session.$serverHash" to LegacyRead.Value(session),
            "kodakiko.mk.$serverHash" to (masterKeyRead ?: LegacyRead.Value(encodeBytes(masterKey))),
            "kodakiko.mkauth.$serverHash" to LegacyRead.Value(authMode),
        ),
    )

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun adoption(legacy: LegacySecrets, target: AdoptionTarget) = LegacyAdoption(context, sodium, legacy, target)

    @Test
    fun aV031ServerInstallIsAdoptedWithItsMasterKey() {
        install("server", serverHash)
        val masterKey = fixtureMasterKey()
        val legacy = serverLegacy(masterKey)
        val target = FakeTarget()
        val result = adoption(legacy, target).run()
        assertEquals(AdoptionStatus.ADOPTED, result.status)
        assertEquals(serverHash, result.accountHash)
        assertTrue(result.masterKeyCarried)
        assertFalse(result.needsPassword)
        assertEquals("http://10.0.2.2:9580", target.serverUrl)
        assertEquals(ActiveAccount(2, serverAccountKey, "k007server", AccountMode.SERVER), target.account)
        assertEquals("{\"apiVersion\":1,\"signupMode\":\"invite\"}", target.config)
        assertEquals(session, target.sessions[serverHash])
        assertArrayEquals(masterKey, target.masterKeys[serverHash])
        assertEquals(1, legacy.forgotten)
        val database = KodakikoDatabase.open(context, serverHash)
        try {
            assertTrue(database.kvGetJson(StorageKeys.MIGRATION_V031_ADOPTED) != null)
            assertEquals(8, database.transaction { it.entries.all().size })
        } finally {
            database.close()
        }
        assertEquals(4, File(context.filesDir, "kodakiko/$serverHash/objects").listFiles()!!.size)
    }

    @Test
    fun runningAgainAfterAdoptionDoesNothing() {
        install("server", serverHash)
        val masterKey = fixtureMasterKey()
        adoption(serverLegacy(masterKey), FakeTarget()).run()
        val target = FakeTarget()
        val result = adoption(serverLegacy(masterKey), target).run()
        assertEquals(AdoptionStatus.ALREADY_ADOPTED, result.status)
        assertEquals(0, target.calls)
    }

    @Test
    fun aFreshInstallHasNothingToAdopt() {
        val legacy = FakeLegacy(mutableMapOf())
        val target = FakeTarget()
        val result = adoption(legacy, target).run()
        assertEquals(AdoptionStatus.FRESH_INSTALL, result.status)
        assertNull(result.accountHash)
        assertEquals(0, target.calls)
        assertEquals(0, legacy.forgotten)
    }

    @Test
    fun aBiometricOrUnreadableMasterKeyFallsBackToThePassword() {
        install("server", serverHash)
        val masterKey = fixtureMasterKey()
        for (read in listOf(LegacyRead.NeedsAuthentication, LegacyRead.Unreadable("bad tag"), LegacyRead.Value("not-base64url!"), LegacyRead.Value(encodeBytes(ByteArray(31))))) {
            File(context.filesDir, "SQLite").deleteRecursively()
            install("server", serverHash)
            val target = FakeTarget()
            val result = adoption(serverLegacy(masterKey, "biometric", read), target).run()
            assertEquals("$read", AdoptionStatus.ADOPTED, result.status)
            assertFalse("$read", result.masterKeyCarried)
            assertTrue("$read", result.needsPassword)
            assertTrue("$read", target.masterKeys.isEmpty())
            assertEquals("$read", session, target.sessions[serverHash])
        }
    }

    @Test
    fun aMasterKeyThatDoesNotOpenTheJournalIsNotCarried() {
        install("server", serverHash)
        val target = FakeTarget()
        val result = adoption(serverLegacy(ByteArray(32) { 7 }), target).run()
        assertEquals(AdoptionStatus.ADOPTED, result.status)
        assertFalse(result.masterKeyCarried)
        assertTrue(result.needsPassword)
        assertTrue(target.masterKeys.isEmpty())
    }

    @Test
    fun aCrashAtAnyStepLeavesTheLegacyItemsAndTheNextStartFinishes() {
        val steps = listOf("serverUrl", "activeAccount", "serverConfig", "session", "masterKey")
        install("server", serverHash)
        val masterKey = fixtureMasterKey()
        for (failAt in steps) {
            File(context.filesDir, "SQLite").deleteRecursively()
            install("server", serverHash)
            val legacy = serverLegacy(masterKey)
            val crashing = FakeTarget(failAt)
            runCatching { adoption(legacy, crashing).run() }
            assertEquals(failAt, 0, legacy.forgotten)
            val database = KodakikoDatabase.open(context, serverHash)
            try {
                assertNull(failAt, database.kvGetJson(StorageKeys.MIGRATION_V031_ADOPTED))
            } finally {
                database.close()
            }
            val target = FakeTarget()
            val result = adoption(legacy, target).run()
            assertEquals(failAt, AdoptionStatus.ADOPTED, result.status)
            assertArrayEquals(failAt, masterKey, target.masterKeys[serverHash])
            assertEquals(failAt, 1, legacy.forgotten)
        }
    }

    @Test
    fun aLocalOnlyInstallIsAdoptedWithoutServerItems() {
        install("local", localHash)
        val legacy = FakeLegacy(
            mutableMapOf(
                "kodakiko.activeAccount" to LegacyRead.Value("{\"schemaVersion\":2,\"accountKey\":\"$localAccountKey\",\"username\":\"k007local\",\"mode\":\"local\"}"),
                "kodakiko.mk.$localHash" to LegacyRead.NeedsAuthentication,
                "kodakiko.mkauth.$localHash" to LegacyRead.Value("biometric"),
            ),
        )
        val target = FakeTarget()
        val result = adoption(legacy, target).run()
        assertEquals(AdoptionStatus.ADOPTED, result.status)
        assertEquals(localHash, result.accountHash)
        assertEquals(ActiveAccount(2, localAccountKey, "k007local", AccountMode.LOCAL), target.account)
        assertNull(target.serverUrl)
        assertTrue(target.sessions.isEmpty())
        assertTrue(result.needsPassword)
    }

    @Test
    fun aNewerOrCorruptDatabaseStopsWithoutTouchingAnything() {
        install("server", serverHash)
        val file = File(context.filesDir, "SQLite/kodakiko-$serverHash.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("PRAGMA user_version = 2") }
        val before = sha256(file)
        val legacy = serverLegacy(ByteArray(32))
        val target = FakeTarget()
        assertEquals(AdoptionStatus.NEWER_DATABASE, adoption(legacy, target).run().status)
        assertEquals(before, sha256(file))
        assertEquals(0, legacy.forgotten)
        assertEquals(0, target.calls)

        File(context.filesDir, "SQLite").deleteRecursively()
        val missing = FakeTarget()
        val missingLegacy = serverLegacy(ByteArray(32))
        assertEquals(AdoptionStatus.DATABASE_MISSING, adoption(missingLegacy, missing).run().status)
        assertEquals(0, missingLegacy.forgotten)
        assertEquals(0, missing.calls)
    }

    @Test
    fun anUnreadableActiveAccountIsTreatedAsAFreshInstallWithoutForgettingAnything() {
        install("server", serverHash)
        val legacy = serverLegacy(ByteArray(32)).also { it.items["kodakiko.activeAccount"] = LegacyRead.Unreadable("bad") }
        val target = FakeTarget()
        assertEquals(AdoptionStatus.FRESH_INSTALL, adoption(legacy, target).run().status)
        assertEquals(0, legacy.forgotten)
        assertEquals(0, target.calls)
    }
}
