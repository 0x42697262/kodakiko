package dev.kodakiko.app.security

import android.view.WindowManager
import dev.kodakiko.app.navigation.AppWiringHarness
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class SecureScreenWiringTest : AppWiringHarness() {
    private fun secure(): Boolean {
        var flags = 0
        scenario!!.onActivity { flags = it.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    @Test
    @Config(application = InsecureScreenV031App::class)
    fun withSecureScreenOffTheLockScreenStaysSecureAndOnlyTheUnlockedAppDropsTheFlag() {
        launch()
        awaitTag(TestTags.Lock.TITLE)
        assertTrue("the lock screen at start is not FLAG_SECURE", secure())

        enterPassword()
        awaitTag(listItem(TestTags.Gallery.TILE, 0), UNLOCK_WAIT_MS)
        waitIdling(WAIT_MS) { !secure() }
        assertFalse("secureScreen=false was not applied while unlocked", secure())

        val reachedClearIndex = AtomicBoolean(false)
        val release = CountDownLatch(1)
        app.graph.indexService.subscribe {
            if (app.graph.accountState.value is AccountState.Locked && reachedClearIndex.compareAndSet(false, true)) {
                release.await(UNLOCK_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }
        try {
            lockFromGallery()
            assertTrue(waitIdling(WAIT_MS) { reachedClearIndex.get() })
            compose.waitForIdle()
            assertTrue("the lock screen is not FLAG_SECURE while the settings are still loaded", secure())
        } finally {
            release.countDown()
        }
        compose.waitForIdle()
        assertTrue("the lock screen after the lock steps is not FLAG_SECURE", secure())
    }
}
