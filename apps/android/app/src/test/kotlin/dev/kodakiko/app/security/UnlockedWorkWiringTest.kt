package dev.kodakiko.app.security

import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.navigation.AppWiringHarness
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class UnlockedWorkWiringTest : AppWiringHarness() {
    @Test
    @Config(application = UpdatedV031App::class)
    fun aJobInTheUnlockedWorkScopeIsCancelledAndJoinedBeforeTheIndexAndTheKeysAreCleared() {
        unlockToGallery()
        assertNotNull(app.graph.indexService.index())
        val indexAtCleanup = AtomicReference<Boolean?>(null)
        val job = app.graph.unlockedWork.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                indexAtCleanup.set(app.graph.indexService.index() != null)
            }
        }

        lockAndWait()

        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
        assertEquals(true, indexAtCleanup.get())
    }
}
