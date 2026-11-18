package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.service.links.CreateLinkInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class B083Test : E2eTestBase() {
    override val server: E2eServer get() = harness

    @Test
    fun whenACreatesALinkChoosingSomePhotosAndVideosWithNotesOffThenAViewerWithoutAnAccountSeesExactlyThoseWithoutNotesLocationOrDates() = e2e {
        val a = signUpDevice("A")
        a.config.refresh()
        a.syncUntilIdle()
        val entry = a.createSyncedEntry(
            EntryInput(
                listOf(server.media(ControlRoutes.PHOTO_WITH_GPS), server.media(ControlRoutes.VIDEO_WITH_LOCATION), server.media(ControlRoutes.PHOTO_WITH_GPS)),
                "a private note",
                listOf("trip"),
            ),
        )
        val chosen = setOf(entry.mediaIdAt(0), entry.mediaIdAt(1))

        val link = a.links.create(CreateLinkInput(listOf(entry.entryId), chosen, TITLE, LinkExpiry.SEVEN_DAYS, includeNotes = false)) {}

        val opened = server.openLink(link.url, read = true)
        assertTrue(opened.toString(), opened.flag(ControlFields.OK))
        val items = opened.items()
        assertEquals(chosen.size, items.size)
        assertEquals(listOf(PublicLinksTest.PHOTO_KIND, PublicLinksTest.VIDEO_KIND), items.map { it.text(ControlFields.KIND) }.sorted())
        assertTrue(items.none { it.text(ControlFields.MEDIA_ID) in entry.mediaIds })
        assertTrue(items.all { it.text(ControlFields.NOTE).isEmpty() })
        val photo = items.single { it.text(ControlFields.KIND) == PublicLinksTest.PHOTO_KIND }
        val video = items.single { it.text(ControlFields.KIND) == PublicLinksTest.VIDEO_KIND }
        assertTrue(photo.isNull(ControlFields.GPS))
        assertTrue(photo.isNull(ControlFields.DATE_TIME_ORIGINAL))
        assertTrue(video.isNull(ControlFields.LOCATION))
        assertTrue(video.isNull(ControlFields.CREATION_UTC))
    }

    companion object {
        private const val TITLE = "Some of the trip"

        @get:ClassRule
        @JvmStatic
        val harness = E2eServer(mapOf(E2eConstants.FLAG_SHARING to E2eConstants.FLAG_OFF, E2eConstants.FLAG_PUBLIC_LINKS to E2eConstants.FLAG_ON))
    }
}
