package dev.kodakiko.ui.feature.auth

import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class CancelDeletionViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()
    private var stored: String? = "2026-11-21T10:00:00.000Z"
    private var reads = 0

    private fun TestScope.open(zone: ZoneId = ZoneOffset.UTC): Pair<CancelDeletionViewModel, Observed<CancelDeletionState, AuthEvent>> {
        val vm = CancelDeletionViewModel(
            auth = auth,
            dueAt = DeletionDueAt {
                reads++
                stored
            },
            zone = zone,
        )
        return vm to observe(vm.state, vm.events)
    }

    @Test
    fun formatsTheDueDateAsALongUsDateInTheDeviceZone() {
        assertEquals("November 21, 2026", formatDeletionDate("2026-11-21T10:00:00.000Z", ZoneOffset.UTC))
        assertEquals("November 20, 2026", formatDeletionDate("2026-11-21T10:00:00.000Z", ZoneOffset.ofHours(-12)))
        assertEquals("January 1, 2027", formatDeletionDate("2026-12-31T20:00:00.000Z", ZoneOffset.ofHours(9)))
    }

    @Test
    fun showsTheStoredDueDate() = runTest {
        val (_, screen) = open()
        assertEquals("November 21, 2026", screen.value.date)
        assertEquals(1, reads)
        assertNull(screen.value.error)
        assertFalse(screen.value.busy)
    }

    @Test
    fun withoutAStoredDateTheDateIsEmpty() = runTest {
        stored = null
        val (_, screen) = open()
        assertEquals("", screen.value.date)
    }

    @Test
    fun keepMyAccountCancelsTheDeletionAndGoesHome() = runTest {
        val (vm, screen) = open()
        vm.onKeep()
        assertEquals(1, auth.count("cancelDeletion"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
    }

    @Test
    fun aFailedCancelShowsItsMessageAndStays() = runTest {
        auth.failures["cancelDeletion"] = AuthError(AuthFailure.UNREACHABLE)
        val (vm, screen) = open()
        vm.onKeep()
        assertEquals(TestTags.Errors.UNREACHABLE, screen.value.error?.testTag)
        assertTrue(screen.events.isEmpty())
        assertFalse(screen.value.busy)
    }

    @Test
    fun isBusyWhileCancellingAndCancelsOnce() = runTest {
        val gate = auth.hold("cancelDeletion")
        val (vm, screen) = open()
        vm.onKeep()
        assertTrue(screen.value.busy)
        vm.onKeep()
        vm.onSignOut()
        assertEquals(1, auth.count("cancelDeletion"))
        assertEquals(0, auth.count("signOut"))
        gate.complete(Unit)
        assertFalse(screen.value.busy)
    }

    @Test
    fun signOutSignsOutAndReturnsToWelcome() = runTest {
        val (vm, screen) = open()
        vm.onSignOut()
        assertEquals(1, auth.count("signOut"))
        assertEquals(0, auth.count("cancelDeletion"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Welcome)), screen.events)
    }
}
