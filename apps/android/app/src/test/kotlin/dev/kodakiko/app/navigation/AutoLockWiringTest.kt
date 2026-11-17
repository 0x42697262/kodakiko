package dev.kodakiko.app.navigation

import android.content.ContentProvider
import android.os.Looper
import androidx.lifecycle.Lifecycle
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class AutoLockWiringTest : AppWiringHarness() {
    @Before
    fun startProcessLifecycle() {
        @Suppress("UNCHECKED_CAST")
        Robolectric.setupContentProvider(Class.forName(INITIALIZATION_PROVIDER) as Class<ContentProvider>)
    }

    private fun locked(): Boolean = app.graph.accountState.value is AccountState.Locked

    private fun pressHomeAndWait() {
        scenario!!.moveToState(Lifecycle.State.CREATED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PAST_ONE_MINUTE_MS))
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (!locked() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS))
            Thread.sleep(STEP_MS)
        }
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun u15TwinOneMinuteChosenInSecurityLocksTheJournalSeventySecondsAfterHome() {
        unlockToGallery()
        push(Route.Settings)
        click(TestTags.Settings.ROW_SECURITY)
        click(TestTags.Settings.AUTO_LOCK)
        tap(TestTags.Settings.AUTO_LOCK + ".1")
        awaitGone(TestTags.Settings.AUTO_LOCK + ".dialog")
        pressHomeAndWait()
        assertTrue(locked())
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitTag(TestTags.Lock.TITLE)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theDefaultFiveMinutesDoNotLockSeventySecondsAfterHome() {
        unlockToGallery()
        pressHomeAndWait()
        assertTrue(app.graph.accountState.value is AccountState.Unlocked)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitTag(listItem(TestTags.Gallery.TILE, 0))
    }

    private companion object {
        const val INITIALIZATION_PROVIDER = "androidx.startup.InitializationProvider"
        const val PAST_ONE_MINUTE_MS = 70_000L
        const val SETTLE_MS = 10_000L
        const val STEP_MS = 50L
    }
}
