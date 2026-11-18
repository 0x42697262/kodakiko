package dev.kodakiko.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.navigation.AppWiringHarness
import dev.kodakiko.service.account.AccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class LockFenceTest : AppWiringHarness() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(application = UpdatedV031App::class)
    fun plaintextWrittenDuringTheLockStepsAfterTheTempWipeIsGoneWhenTheLockCompletes() {
        unlockToGallery()
        val late = listOf(Leftovers.lateShare(context), Leftovers.lateTemp(context))
        val wrote = AtomicBoolean(false)
        app.graph.indexService.subscribe {
            if (app.graph.accountState.value is AccountState.Locked && wrote.compareAndSet(false, true)) late.forEach(Leftovers::write)
        }

        lockAndWait()

        assertTrue("the late writer never ran during clearIndex", wrote.get())
        assertEquals(emptyList<String>(), Leftovers.remaining(late))
    }
}
