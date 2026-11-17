package dev.kodakiko.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.navigation.AppWiringHarness
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TempWipeTest : AppWiringHarness() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun awaitReady() {
        check(waitIdling(WAIT_MS) { app.graph.startup.ready.value }) { "the app never became ready" }
    }

    private fun awaitWiped(files: List<File>, timeoutMs: Long): List<String> {
        waitIdling(timeoutMs) { Leftovers.remaining(files).isEmpty() }
        return Leftovers.remaining(files)
    }

    @Test
    @Config(application = AdoptionWithLeftoversApp::class)
    fun aStartWithAPendingV031AdoptionWipesLeftoverPlaintextBeforeItIsReady() {
        assertEquals(Leftovers.all(context).map { it.path }, Leftovers.remaining(Leftovers.all(context)))
        launch()
        awaitReady()
        assertEquals(emptyList<String>(), Leftovers.remaining(Leftovers.all(context)))
    }

    @Test
    @Config(application = RestoreWithLeftoversApp::class)
    fun aNormalStartWipesLeftoverPlaintextBeforeItIsReady() {
        assertEquals(Leftovers.all(context).map { it.path }, Leftovers.remaining(Leftovers.all(context)))
        launch()
        awaitReady()
        assertEquals(emptyList<String>(), Leftovers.remaining(Leftovers.all(context)))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun unlockingWipesPlaintextLeftSinceTheStart() {
        launch()
        awaitTag(TestTags.Lock.TITLE)
        awaitReady()
        val seeded = Leftovers.seed(context)
        compose.waitForIdle()
        assertEquals(seeded.map { it.path }, Leftovers.remaining(seeded))

        enterPassword()
        awaitTag(listItem(TestTags.Gallery.TILE, 0), UNLOCK_WAIT_MS)

        assertEquals(emptyList<String>(), awaitWiped(seeded, WAIT_MS))
    }
}
