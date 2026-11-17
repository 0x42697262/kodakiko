package dev.kodakiko.data.migration

import android.content.Context
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
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.LegacyRead
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LegacyMasterKeyBytesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sodium = createSodium()
    private val serverHash = "72a22fb39f638ca4"
    private val serverAccountKey = "10.0.2.2:9580/pufepQc0df0nbPs1EifhgA"
    private val masterKeyName = "kodakiko.mk.$serverHash"

    private val fixture: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
            .let { File(it, "tests/fixtures/v031-device/server") }

    private fun install() {
        val sqlite = File(context.filesDir, "SQLite").apply { mkdirs() }
        for (suffix in listOf("", "-wal", "-shm")) {
            File(fixture, "files/SQLite/kodakiko-$serverHash.db$suffix").copyTo(File(sqlite, "kodakiko-$serverHash.db$suffix"), overwrite = true)
        }
        File(fixture, "files/kodakiko").copyRecursively(File(context.filesDir, "kodakiko"), overwrite = true)
    }

    private fun fixtureMasterKey(): ByteArray {
        val password = Json.parseToJsonElement(File(fixture, "account.json").readText()).jsonObject.getValue("PASSWORD").jsonPrimitive.content
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

    private class ByteLegacy(private val texts: Map<String, String>, private val masterKeyName: String, private val masterKeyText: String) : LegacySecrets {
        val textReads = mutableListOf<String>()
        val handedOut = mutableListOf<ByteArray>()

        override fun read(name: String): LegacyRead {
            textReads += name
            if (name == masterKeyName) return LegacyRead.Value(masterKeyText)
            return texts[name]?.let { LegacyRead.Value(it) } ?: LegacyRead.Missing
        }

        override fun readBytes(name: String): LegacyRead {
            val text = if (name == masterKeyName) masterKeyText else texts[name] ?: return LegacyRead.Missing
            val bytes = text.toByteArray(Charsets.US_ASCII)
            if (name == masterKeyName) handedOut += bytes
            return LegacyRead.Bytes(bytes)
        }

        override fun forgetAll() = Unit
    }

    private class KeyTarget : AdoptionTarget {
        var masterKey: ByteArray? = null

        override fun saveServerUrl(url: String) = Unit

        override fun saveActiveAccount(account: ActiveAccount) = Unit

        override fun saveServerConfigJson(json: String) = Unit

        override fun saveSession(accountHash: String, token: String) = Unit

        override fun saveMasterKey(accountHash: String, masterKey: ByteArray): Boolean {
            this.masterKey = masterKey.copyOf()
            return true
        }
    }

    private fun legacy(masterKey: ByteArray) = ByteLegacy(
        mapOf(
            "kodakiko.activeAccount" to "{\"schemaVersion\":2,\"accountKey\":\"$serverAccountKey\",\"username\":\"k007server\",\"mode\":\"server\"}",
            "kodakiko.mkauth.$serverHash" to "device",
        ),
        masterKeyName,
        encodeBytes(masterKey),
    )

    @Test
    fun theLegacyMasterKeyIsReadOnlyThroughTheBytePath() {
        install()
        val masterKey = fixtureMasterKey()
        val legacy = legacy(masterKey)
        val target = KeyTarget()

        val result = LegacyAdoption(context, sodium, legacy, target).run()

        assertEquals(AdoptionStatus.ADOPTED, result.status)
        assertFalse("the master key went through the String path", masterKeyName in legacy.textReads)
        assertTrue(result.masterKeyCarried)
        assertArrayEquals(masterKey, target.masterKey)
    }

    @Test
    fun theBytesOfTheLegacyMasterKeyAreWipedAfterAdoption() {
        install()
        val legacy = legacy(fixtureMasterKey())

        LegacyAdoption(context, sodium, legacy, KeyTarget()).run()

        val handed = legacy.handedOut
        assertEquals(1, handed.size)
        assertTrue("the legacy master key bytes were not wiped", handed.single().all { it == 0.toByte() })
    }

    @Test
    fun theByteReadIsRedactedWhenPrinted() {
        val read = LegacyRead.Bytes(byteArrayOf(65, 66, 67))
        assertFalse(read.toString().contains("ABC"))
        assertFalse(read.toString().contains("65"))
    }
}
