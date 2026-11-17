package dev.kodakiko.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.GlobalStore
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

object V031Fixture {
    const val HASH = "b1abbb91a5439ec1"
    const val ACCOUNT_KEY = "local/AJodCY5u6JCoGljpGC5X5w"
    const val USERNAME = "k007local"
    const val PASSWORD = "correct horse battery staple"

    private const val UNAUTHENTICATED_ALIAS = "AES/GCM/NoPadding:key_v1:keystoreUnauthenticated"

    private val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
    }

    private val local: File get() = File(repoRoot, "tests/fixtures/v031-device/local")

    fun databaseFile(context: Context): File = KodakikoDatabase.databaseFile(context, HASH)

    fun install(context: Context) {
        val sqlite = File(context.filesDir, "SQLite").apply { mkdirs() }
        for (suffix in listOf("", "-wal", "-shm")) {
            File(local, "files/SQLite/kodakiko-$HASH.db$suffix").copyTo(File(sqlite, "kodakiko-$HASH.db$suffix"), overwrite = true)
        }
        File(local, "files/kodakiko").copyRecursively(File(context.filesDir, "kodakiko"), overwrite = true)
    }

    fun corrupt(context: Context) {
        install(context)
        KodakikoDatabase.open(context, HASH).close()
        val file = databaseFile(context)
        for (suffix in listOf("-wal", "-shm", "-journal")) File(file.path + suffix).delete()
        val bytes = file.readBytes()
        val declared = ((bytes[16].toInt() and 0xFF) shl 8) or (bytes[17].toInt() and 0xFF)
        val pageSize = if (declared == 1) 65_536 else declared
        for (index in pageSize until bytes.size) bytes[index] = 0x5A
        file.writeBytes(bytes)
    }

    fun writeLegacyActiveAccount(context: Context) {
        writeLegacy(
            context,
            "kodakiko.activeAccount",
            "{\"schemaVersion\":2,\"accountKey\":\"$ACCOUNT_KEY\",\"username\":\"$USERNAME\",\"mode\":\"local\"}",
        )
    }

    fun writeLegacyItem(context: Context, name: String, value: String) {
        writeLegacy(context, name, value)
    }

    private fun writeLegacy(context: Context, name: String, value: String) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(UNAUTHENTICATED_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, generator.generateKey())
        val gcm = cipher.parameters.getParameterSpec(GCMParameterSpec::class.java)
        val item = JSONObject()
            .put("ct", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(gcm.iv, Base64.NO_WRAP))
            .put("tlen", gcm.tLen)
            .put("scheme", "aes")
            .put("usesKeystoreSuffix", true)
            .put("keystoreAlias", "key_v1")
            .put("requireAuthentication", false)
        check(context.getSharedPreferences("SecureStore", Context.MODE_PRIVATE).edit().putString("key_v1-$name", item.toString()).commit())
    }

    fun legacyItemNames(context: Context): Set<String> = context.getSharedPreferences("SecureStore", Context.MODE_PRIVATE).all.keys

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

class LockedLocalAccountApp : KodakikoApp() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        GlobalStore(this).setActiveAccount(ActiveAccount(ActiveAccount.CURRENT_SCHEMA_VERSION, "local/AAAAAAAAAAAAAAAAAAAAAA", "alice", AccountMode.LOCAL))
        super.onCreate()
    }
}

class UpdatedV031App : KodakikoApp() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        V031Fixture.install(this)
        V031Fixture.writeLegacyActiveAccount(this)
        super.onCreate()
    }
}

class CorruptV031App : KodakikoApp() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        V031Fixture.corrupt(this)
        seededSha256 = V031Fixture.sha256(V031Fixture.databaseFile(this))
        V031Fixture.writeLegacyActiveAccount(this)
        super.onCreate()
    }

    companion object {
        var seededSha256: String? = null
    }
}
