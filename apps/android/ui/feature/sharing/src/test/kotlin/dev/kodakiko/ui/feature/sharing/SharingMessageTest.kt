package dev.kodakiko.ui.feature.sharing

import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.service.sharing.AddEntriesResult
import dev.kodakiko.service.sharing.SharingErrorReason
import dev.kodakiko.service.sharing.SharingException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharingMessageTest {
    private fun text(error: Throwable, recipient: String = ""): String = sharingMessageText(resources, sharingMessage(error, recipient))

    private fun result(added: Int = 0, notSynced: Int = 0, alreadyIn: Int = 0, failed: Int = 0) = AddEntriesResult(
        added = List(added) { "a$it" },
        alreadyIn = List(alreadyIn) { "i$it" },
        notSynced = List(notSynced) { "n$it" },
        failed = List(failed) { "f$it" },
    )

    @Test
    fun everySharingReasonMapsToItsTypedMessageLikeV031() {
        assertEquals(SharingMessage.Offline, sharingMessage(SharingException(SharingErrorReason.OFFLINE)))
        assertEquals(SharingMessage.UserNotFound, sharingMessage(SharingException(SharingErrorReason.USER_NOT_FOUND)))
        assertEquals(SharingMessage.AlreadyShared("bob"), sharingMessage(SharingException(SharingErrorReason.ALREADY_SHARED), "bob"))
        assertEquals(SharingMessage.FlagOff, sharingMessage(SharingException(SharingErrorReason.DISABLED)))
        assertEquals(SharingMessage.Failed, sharingMessage(SharingException(SharingErrorReason.LOCKED)))
        assertEquals(SharingMessage.Failed, sharingMessage(SharingException(SharingErrorReason.NOT_OWNER)))
    }

    @Test
    fun anythingThatIsNotASharingErrorIsTheGenericFailure() {
        assertEquals(SharingMessage.Failed, sharingMessage(IllegalStateException("boom")))
        assertEquals(SharingMessage.Failed, sharingMessage(NetworkError()))
        assertEquals(SharingMessage.Failed, sharingMessage(RuntimeException(), "bob"))
    }

    @Test
    fun theRecipientNameIsOnlyUsedForAlreadyShared() {
        assertEquals(SharingMessage.UserNotFound, sharingMessage(SharingException(SharingErrorReason.USER_NOT_FOUND), "bob"))
        assertEquals(SharingMessage.AlreadyShared(""), sharingMessage(SharingException(SharingErrorReason.ALREADY_SHARED)))
    }

    @Test
    fun theMessagesReadLikeV031() {
        assertEquals("You're offline. Sharing needs a connection.", text(SharingException(SharingErrorReason.OFFLINE)))
        assertEquals("No user with that username on this server.", text(SharingException(SharingErrorReason.USER_NOT_FOUND)))
        assertEquals("Already shared with bob.", text(SharingException(SharingErrorReason.ALREADY_SHARED), "bob"))
        assertEquals("Sharing is turned off on this server.", text(SharingException(SharingErrorReason.DISABLED)))
        assertEquals("Something went wrong. Try again.", text(SharingException(SharingErrorReason.LOCKED)))
        assertEquals("Something went wrong. Try again.", text(SharingException(SharingErrorReason.NOT_OWNER)))
        assertEquals("Something went wrong. Try again.", text(IllegalArgumentException()))
    }

    @Test
    fun addingEntriesAlwaysSaysHowManyWereAddedThenOnlyTheNonZeroRest() {
        assertEquals("2 added.", addEntriesResultText(resources, result(added = 2)))
        assertEquals("0 added.", addEntriesResultText(resources, result()))
        assertEquals("1 added. 1 not synced yet — try again shortly.", addEntriesResultText(resources, result(added = 1, notSynced = 1)))
        assertEquals("0 added. 3 were already there.", addEntriesResultText(resources, result(alreadyIn = 3)))
        assertEquals(
            "2 added. 1 not synced yet — try again shortly. 1 were already there. 2 couldn't be added.",
            addEntriesResultText(resources, result(added = 2, notSynced = 1, alreadyIn = 1, failed = 2)),
        )
    }
}
