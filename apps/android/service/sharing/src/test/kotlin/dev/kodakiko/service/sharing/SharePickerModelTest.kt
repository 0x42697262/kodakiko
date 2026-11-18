package dev.kodakiko.service.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePickerModelTest {
    private val items = listOf(
        ShareItem(mediaId = "a", bytes = 200_000_000, caption = "Dock"),
        ShareItem(mediaId = "b", bytes = 250_000_000),
        ShareItem(mediaId = "c", bytes = 100_000_000, caption = "Gulls"),
    )

    @Test
    fun countsTheCheckedItemsAndWarnsAboveTheSizeLimit() {
        assertEquals(ShareSelection(count = 2, bytes = 450_000_000, warn = false), shareSelection(items, setOf("a", "b")))
        val all: ShareSelection = shareSelection(items, setOf("a", "b", "c"))
        assertEquals(550_000_000L, all.bytes)
        assertEquals(all.bytes > SharingConstants.SHARE_WARN_BYTES, all.warn)
        assertTrue(all.warn)
        assertEquals(524_288_000L, SharingConstants.SHARE_WARN_BYTES)
    }

    @Test
    fun buildsTheOptionalTextFromCaptionsAndTheNote() {
        val chosen = setOf("a", "b", "c")
        assertNull(shareText(ShareTextOption.NONE, items, chosen, "A note"))
        assertEquals("Dock\nGulls", shareText(ShareTextOption.CAPTIONS, items, chosen, "A note"))
        assertEquals("Dock\nGulls\n\nA note", shareText(ShareTextOption.CAPTIONS_AND_NOTE, items, chosen, "A note"))
        assertNull(shareText(ShareTextOption.CAPTIONS, items, setOf("b"), "A note"))
        assertNull(shareText(ShareTextOption.CAPTIONS_AND_NOTE, items, setOf("b"), ""))
    }

    @Test
    fun theNoteIsTrimmedAndAnEmptyCaptionStillCounts() {
        val captioned = listOf(ShareItem(mediaId = "a", bytes = 1, caption = ""), ShareItem(mediaId = "b", bytes = 1, caption = "Dock"))
        assertEquals("\nDock\n\nA note", shareText(ShareTextOption.CAPTIONS_AND_NOTE, captioned, setOf("a", "b"), "  A note\n"))
        assertEquals("A note", shareText(ShareTextOption.CAPTIONS_AND_NOTE, captioned, setOf("a"), " A note "))
        assertEquals("Dock", shareText(ShareTextOption.CAPTIONS_AND_NOTE, captioned, setOf("b"), "   "))
    }
}
