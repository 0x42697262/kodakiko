package dev.kodakiko.ui.feature.links

import dev.kodakiko.service.links.CreateLinkProgress
import dev.kodakiko.service.links.CreateLinkResult
import dev.kodakiko.service.links.CreateLinkStep
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinkTextTest {
    private fun label(expiresInMs: Long?, maxViews: Long? = null, viewCount: Long = 0): LinkExpiryLabel =
        linkExpiryLabel(ownerLink("l1", "Picnic", expiresAt = expiresInMs?.let { isoAt(NOW_MS + it) }, maxViews = maxViews, viewCount = viewCount), NOW_MS)

    private fun text(expiresInMs: Long?, maxViews: Long? = null, viewCount: Long = 0): String =
        linkExpiryText(resources, label(expiresInMs, maxViews, viewCount))

    private fun result(entries: Int = 0, undecodable: Int = 0, video: Int = 0) =
        CreateLinkResult(url = LINK_URL, linkId = "l1", skippedEntries = entries, skippedUndecodable = undecodable, skippedUnsupportedVideo = video)

    @Test
    fun preparingCountsFromOneUploadingShowsSizesAndRegisteringIsTheLastStep() {
        assertEquals("Preparing 1 of 3…", linkProgressText(resources, CreateLinkProgress(CreateLinkStep.PREPARING, 0, 3, 0, 0)))
        assertEquals("Preparing 3 of 3…", linkProgressText(resources, CreateLinkProgress(CreateLinkStep.PREPARING, 2, 3, 0, 0)))
        assertEquals(
            "Uploading 1.5 MB of 12 MB…",
            linkProgressText(resources, CreateLinkProgress(CreateLinkStep.UPLOADING, 1, 3, 3 * MB / 2, 12 * MB)),
        )
        assertEquals("Uploading 0 B of 700 KB…", linkProgressText(resources, CreateLinkProgress(CreateLinkStep.UPLOADING, 0, 1, 0, 700 * KB)))
        assertEquals("Creating the link…", linkProgressText(resources, CreateLinkProgress(CreateLinkStep.REGISTERING, 0, 1, 12 * MB, 12 * MB)))
    }

    @Test
    fun oneViewLinksSayWhetherTheyWereOpenedWhateverTheirExpiry() {
        assertEquals(LinkExpiryLabel.OneViewUnopened, label(DAY_MS, maxViews = 1, viewCount = 0))
        assertEquals(LinkExpiryLabel.Opened, label(DAY_MS, maxViews = 1, viewCount = 1))
        assertEquals(LinkExpiryLabel.Opened, label(null, maxViews = 1, viewCount = 2))
        assertEquals("One view — not opened yet", text(null, maxViews = 1))
        assertEquals("Opened", text(null, maxViews = 1, viewCount = 1))
    }

    @Test
    fun linksWithoutAnExpiryNeverExpire() {
        assertEquals(LinkExpiryLabel.Never, label(null))
        assertEquals("Never expires", text(null))
    }

    @Test
    fun aDayOrMoreIsRoundedToWholeDays() {
        assertEquals(LinkExpiryLabel.InDays(3), label(3 * DAY_MS))
        assertEquals("Expires in 3 days", text(3 * DAY_MS))
        assertEquals("Expires in 7 days", text(7 * DAY_MS - HOUR_MS))
        assertEquals("Expires in 2 days", text(DAY_MS + DAY_MS / 2))
        assertEquals("Expires in 1 day", text(DAY_MS + DAY_MS / 2 - 1))
        assertEquals("Expires in 1 day", text(DAY_MS / 2))
        assertEquals(LinkExpiryLabel.InDays(1), label(DAY_MS / 2))
    }

    @Test
    fun lessThanHalfADayIsInHoursAtLeastOne() {
        assertEquals(LinkExpiryLabel.InHours(10), label(DAY_MS / 2 - 2 * HOUR_MS))
        assertEquals("Expires in 10 h", text(DAY_MS / 2 - 2 * HOUR_MS))
        assertEquals("Expires in 1 h", text(HOUR_MS))
        assertEquals("Expires in 1 h", text(HOUR_MS / 4))
        assertEquals("Expires in 2 h", text(HOUR_MS + HOUR_MS / 2))
        assertEquals("Expires in 1 h", text(-2 * HOUR_MS))
    }

    @Test
    fun aRowReadsExpiryDotViews() {
        assertEquals("Expires in 3 days · 2 views", linkRowDetail(resources, ownerLink("l1", "Picnic", expiresAt = isoAt(NOW_MS + 3 * DAY_MS), viewCount = 2), NOW_MS))
        assertEquals("Never expires · 1 view", linkRowDetail(resources, ownerLink("l1", "Picnic", viewCount = 1), NOW_MS))
        assertEquals("One view — not opened yet · 0 views", linkRowDetail(resources, ownerLink("l1", "Picnic", maxViews = 1), NOW_MS))
    }

    @Test
    fun skippedItemsAreReportedOnlyWhenThereAreAnyWithSingularAndPlural() {
        assertEquals(emptyList<String>(), linkSkippedLines(resources, result()))
        assertEquals(
            listOf("1 entry was skipped.", "1 photo couldn't be converted.", "1 video is in a format links can't carry."),
            linkSkippedLines(resources, result(entries = 1, undecodable = 1, video = 1)),
        )
        assertEquals(
            listOf("2 entries were skipped.", "3 photos couldn't be converted.", "2 videos are in a format links can't carry."),
            linkSkippedLines(resources, result(entries = 2, undecodable = 3, video = 2)),
        )
        assertEquals(listOf("2 videos are in a format links can't carry."), linkSkippedLines(resources, result(video = 2)))
    }
}
