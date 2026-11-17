package dev.kodakiko.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.app.V031Media
import dev.kodakiko.app.navigation.AppWiringHarness
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.service.lock.LockStepNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
class LifecycleWiringTest : AppWiringHarness() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val appSources: String by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
        File(root, "apps/android/app/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
    }

    private fun constantNames(): Map<String, String> =
        LockStepNames::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.get(null) as String to it.name }

    @Test
    fun everyLockAndUnlockStepNameIsWiredToARealStep() {
        val constants = constantNames()
        val names = LockStepNames.LOCK + LockStepNames.UNLOCK
        val missing = names.filter { name ->
            val constant = constants[name] ?: return@filter true
            !Regex("""LifecycleStep\(\s*LockStepNames\.$constant\b""").containsMatchIn(appSources)
        }
        assertEquals(emptyList<String>(), missing)
        assertFalse("a step is still declared through unwired()", Regex("""\bunwired\s*\(""").containsMatchIn(appSources))
        assertFalse("a step has an empty body", Regex("""LifecycleStep\([^)]*\)\s*\{\s*\}""").containsMatchIn(appSources))
    }

    @Test
    @Config(application = OrphansV031App::class)
    fun unlockingSweepsOrphanedObjectFilesOlderThanAnHourAndKeepsTheRest() {
        val known = V031Media.accountDir(context).resolve("objects").listFiles()!!.filter { it.name != Orphans.OLD_OBJECT + ".kdks" && it.name != Orphans.NEW_OBJECT + ".kdks" }
        assertTrue(known.isNotEmpty())
        assertTrue(Orphans.oldObject(context).isFile)
        assertTrue(Orphans.oldThumbnail(context).isFile)

        unlockToGallery()
        waitIdling(WAIT_MS) { !Orphans.oldObject(context).exists() && !Orphans.oldThumbnail(context).exists() }

        assertFalse("an old orphaned object survived the unlock", Orphans.oldObject(context).exists())
        assertFalse("an old orphaned plain thumbnail survived the unlock", Orphans.oldThumbnail(context).exists())
        assertTrue(Orphans.newObject(context).isFile)
        assertTrue(known.all { it.isFile })
    }

    @Test
    @Config(application = TinyCacheServerV031App::class)
    fun unlockingEnforcesTheOriginalCacheCap() {
        val original = V031Media.objectFile(context, V031Media.ORIGINAL_OBJECT)
        assertTrue(original.isFile)

        unlockToGallery()
        waitIdling(SYNC_WAIT_MS) { !original.exists() }

        assertFalse("an uploaded original over the cap survived the unlock", original.exists())
        val state = KodakikoDatabase.open(context, V031Fixture.HASH).use { database ->
            database.transaction { tx -> tx.objects.get(V031Media.ORIGINAL_OBJECT)?.localState }
        }
        assertEquals(ObjectLocalState.MISSING, state)
    }
}
