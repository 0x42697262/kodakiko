package dev.kodakiko.app.security

import android.content.Context
import dev.kodakiko.app.FakeAndroidKeyStore
import dev.kodakiko.app.FakeBucket
import dev.kodakiko.app.KodakikoApp
import dev.kodakiko.app.ServerFixture
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.app.V031Media
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.GlobalStore
import java.io.File

object Leftovers {
    fun tempImport(context: Context): File = File(context.cacheDir, "kodakiko/${V031Fixture.HASH}/tmp/import-x.jpg")

    fun share(context: Context): File = File(context.cacheDir, "share/a.jpg")

    fun capture(context: Context): File = File(context.cacheDir, "capture/phone-1.jpg")

    fun lateShare(context: Context): File = File(context.cacheDir, "share/late.jpg")

    fun lateTemp(context: Context): File = File(context.cacheDir, "kodakiko/${V031Fixture.HASH}/tmp/import-late.jpg")

    fun all(context: Context): List<File> = listOf(tempImport(context), share(context), capture(context))

    fun seed(context: Context): List<File> = all(context).onEach(::write)

    fun write(file: File) {
        file.parentFile!!.mkdirs()
        file.writeBytes(PLAINTEXT)
    }

    fun remaining(files: List<File>): List<String> = files.filter { it.exists() }.map { it.path }

    private val PLAINTEXT = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
}

object Orphans {
    const val OLD_OBJECT = "oldOrphanObjectAAAAAAA"
    const val NEW_OBJECT = "newOrphanObjectAAAAAAA"
    const val OLD_MEDIA = "oldOrphanMediaAAAAAAAA"
    const val AGE_MS = 2 * 60 * 60 * 1000L

    fun oldObject(context: Context): File = V031Media.objectFile(context, OLD_OBJECT)

    fun newObject(context: Context): File = V031Media.objectFile(context, NEW_OBJECT)

    fun oldThumbnail(context: Context): File = File(V031Media.accountDir(context), "thumbs-plain/$OLD_MEDIA.webp")

    fun seed(context: Context) {
        val old = System.currentTimeMillis() - AGE_MS
        for (file in listOf(oldObject(context), oldThumbnail(context))) {
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
            check(file.setLastModified(old))
        }
        newObject(context).writeBytes(byteArrayOf(4, 5, 6))
    }
}

class AdoptionWithLeftoversApp : KodakikoApp() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        V031Fixture.install(this)
        V031Fixture.writeLegacyActiveAccount(this)
        Leftovers.seed(this)
        super.onCreate()
    }
}

class RestoreWithLeftoversApp : KodakikoApp() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        GlobalStore(this).setActiveAccount(ActiveAccount(ActiveAccount.CURRENT_SCHEMA_VERSION, "local/AAAAAAAAAAAAAAAAAAAAAA", "alice", AccountMode.LOCAL))
        Leftovers.seed(this)
        super.onCreate()
    }
}

class OrphansV031App : KodakikoApp() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        V031Fixture.install(this)
        V031Fixture.writeLegacyActiveAccount(this)
        Orphans.seed(this)
        super.onCreate()
    }
}

class InsecureScreenV031App : KodakikoApp() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        V031Fixture.install(this)
        V031Media.writeSettings(this, DeviceSettingsDefaults.ANDROID.copy(secureScreen = false))
        V031Fixture.writeLegacyActiveAccount(this)
        super.onCreate()
    }
}

class TinyCacheServerV031App : KodakikoApp() {
    override fun onCreate() {
        ServerFixture.install(
            this,
            sharingEnabled = true,
            bucketMode = FakeBucket.Mode.SERVE,
            settings = DeviceSettingsDefaults.ANDROID.copy(originalCacheCapBytes = 1),
        )
        KodakikoDatabase.open(this, V031Fixture.HASH).use { database ->
            database.transaction { tx ->
                val row = requireNotNull(tx.objects.get(V031Media.ORIGINAL_OBJECT))
                tx.objects.put(row.copy(localState = ObjectLocalState.PRESENT, uploadState = ObjectUploadState.UPLOADED))
            }
        }
        check(V031Media.objectFile(this, V031Media.ORIGINAL_OBJECT).isFile)
        super.onCreate()
    }
}
