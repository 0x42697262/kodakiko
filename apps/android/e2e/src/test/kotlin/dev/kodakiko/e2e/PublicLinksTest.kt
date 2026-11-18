package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.service.links.CreateLinkInput
import dev.kodakiko.service.links.CreateLinkResult
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean

fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

fun JsonObject.isNull(name: String): Boolean = get(name) is JsonNull

fun JsonObject.items(): List<JsonObject> = getValue(ControlFields.ITEMS).jsonArray.map { it.jsonObject }

suspend fun E2eDevice.createLink(entryIds: List<String>, expiry: LinkExpiry, title: String, includeNotes: Boolean): CreateLinkResult {
    val mediaIds = LinkedHashSet<String>()
    for (entryId in entryIds) for (media in readMetadata(entryId).media) mediaIds += media.mediaId
    return links.create(CreateLinkInput(entryIds, mediaIds, title, expiry, includeNotes)) {}
}

@RunWith(AndroidJUnit4::class)
class PublicLinksTest : E2eTestBase() {
    override val server: E2eServer get() = harness

    @Test
    fun aViewerWithoutAnAccountDecryptsStrippedCopiesOneViewAndRevokeStopAccess() = e2e {
        val a = signUpDevice("A")
        a.config.refresh()
        a.syncUntilIdle()
        val photoEntry = a.createSyncedEntry(EntryInput(listOf(server.media(ControlRoutes.PHOTO_WITH_GPS)), "beach", listOf("trip")))
        val videoEntry = a.createSyncedEntry(EntryInput(listOf(server.media(ControlRoutes.VIDEO_WITH_LOCATION)), "waves", emptyList()))
        assertTrue(photoEntry.mediaIdAt(0).isNotEmpty())

        val link = a.createLink(listOf(photoEntry.entryId, videoEntry.entryId), LinkExpiry.SEVEN_DAYS, TITLE, includeNotes = true)
        val opened = server.openLink(link.url, read = true)
        assertTrue(opened.toString(), opened.flag(ControlFields.OK))
        assertEquals(TITLE, opened.text(ControlFields.TITLE))
        assertEquals(2, opened.items().size)
        val photo = opened.items().first { it.text(ControlFields.KIND) == PHOTO_KIND }
        val video = opened.items().first { it.text(ControlFields.KIND) == VIDEO_KIND }
        assertEquals("beach", photo.text(ControlFields.NOTE))
        assertTrue(photo.isNull(ControlFields.GPS))
        assertTrue(photo.isNull(ControlFields.DATE_TIME_ORIGINAL))
        assertTrue(video.isNull(ControlFields.LOCATION))
        assertTrue(video.isNull(ControlFields.CREATION_UTC))
        assertTrue(photo.getValue(ControlFields.THUMBNAIL_BYTES).jsonPrimitive.int > 0)

        val once = a.createLink(listOf(photoEntry.entryId), LinkExpiry.ONE_VIEW, TITLE, includeNotes = true)
        assertTrue(server.openLink(once.url, read = false).flag(ControlFields.OK))
        val secondView = server.openLink(once.url, read = false)
        assertFalse(secondView.flag(ControlFields.OK))
        assertEquals(LINK_EXPIRED, secondView.text(ControlFields.CODE))

        a.links.revoke(link.linkId)
        val afterRevoke = server.openLink(link.url, read = false)
        assertFalse(afterRevoke.flag(ControlFields.OK))
        assertTrue(afterRevoke.flag(ControlFields.API_ERROR))
        assertEquals(LINK_TOKEN_INVALID, afterRevoke.text(ControlFields.CODE))

        val listed = a.links.list()
        assertEquals(listOf(once.linkId), listed.map { it.linkId })
        assertEquals(once.url, listed[0].url)
    }

    companion object {
        const val TITLE = "Holiday"
        const val PHOTO_KIND = "photo"
        const val VIDEO_KIND = "video"
        const val LINK_EXPIRED = "LINK_EXPIRED"
        const val LINK_TOKEN_INVALID = "LINK_TOKEN_INVALID"

        @get:ClassRule
        @JvmStatic
        val harness = E2eServer(mapOf(E2eConstants.FLAG_SHARING to E2eConstants.FLAG_OFF, E2eConstants.FLAG_PUBLIC_LINKS to E2eConstants.FLAG_ON))
    }
}
