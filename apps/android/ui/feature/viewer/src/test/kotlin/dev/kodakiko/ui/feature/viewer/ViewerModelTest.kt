package dev.kodakiko.ui.feature.viewer

import androidx.compose.ui.geometry.Offset
import androidx.media3.common.PlaybackException
import dev.kodakiko.crypto.DecryptionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ViewerModelTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, TOLERANCE)

    private fun near(expected: Offset, actual: Offset) {
        assertEquals("x of $actual", expected.x, actual.x, TOLERANCE)
        assertEquals("y of $actual", expected.y, actual.y, TOLERANCE)
    }

    private fun landscape() = PhotoZoomState(viewportWidth = 400f, viewportHeight = 400f, imageWidth = 4000f, imageHeight = 3000f)

    @Test
    fun theZoomConstantsAreV031s() {
        assertEquals(1.0, ViewerConstants.ZOOM_MIN, 0.0)
        assertEquals(5.0, ViewerConstants.ZOOM_MAX, 0.0)
        assertEquals(2.5, ViewerConstants.ZOOM_DOUBLE_TAP, 0.0)
        assertEquals(1.05, ViewerConstants.ZOOM_SNAP_BELOW, 0.0)
        assertEquals(800L, ViewerConstants.AUTOPLAY_SETTLE_MS)
        assertEquals(1, ViewerConstants.ORIGINAL_WINDOW)
    }

    @Test
    fun keepsTheScaleWithinTheZoomLimits() {
        assertEquals(ViewerConstants.ZOOM_MIN, ZoomMath.clampScale(0.5), 0.0)
        assertEquals(2.0, ZoomMath.clampScale(2.0), 0.0)
        assertEquals(ViewerConstants.ZOOM_MAX, ZoomMath.clampScale(9.0), 0.0)
    }

    @Test
    fun snapsAScaleJustAbove1BackTo1WhenTheGestureEnds() {
        assertEquals(1.0, ZoomMath.settleScale(1.04), 0.0)
        assertEquals(1.05, ZoomMath.settleScale(1.05), 0.0)
        assertEquals(3.0, ZoomMath.settleScale(3.0), 0.0)
    }

    @Test
    fun allowsNoMovementWhileTheZoomedImageStillFitsTheViewport() {
        assertEquals(0.0, ZoomMath.maxTranslate(300.0, 400.0, 1.0), 0.0)
        assertEquals(0.0, ZoomMath.maxTranslate(200.0, 400.0, 1.5), 0.0)
    }

    @Test
    fun allowsHalfOfTheOverflowOnEachSide() {
        assertEquals(200.0, ZoomMath.maxTranslate(400.0, 400.0, 2.0), 0.0)
        assertEquals(250.0, ZoomMath.maxTranslate(400.0, 300.0, 2.0), 0.0)
    }

    @Test
    fun handlesAPortraitPhotoNarrowerThanTheViewport() {
        assertEquals(25.0, ZoomMath.maxTranslate(225.0, 400.0, 2.0), 0.0)
        assertEquals(0.0, ZoomMath.maxTranslate(225.0, 400.0, 1.5), 0.0)
    }

    @Test
    fun clampsATranslationToTheAllowedRangeOnBothSides() {
        assertEquals(200.0, ZoomMath.clampTranslate(500.0, 200.0), 0.0)
        assertEquals(-200.0, ZoomMath.clampTranslate(-500.0, 200.0), 0.0)
        assertEquals(120.0, ZoomMath.clampTranslate(120.0, 200.0), 0.0)
        assertEquals(0.0, ZoomMath.clampTranslate(30.0, 0.0), 0.0)
    }

    @Test
    fun keepsTheContentPointUnderTheFocalPointFixedOnScreen() {
        val center = 200.0
        val cases = listOf(
            doubleArrayOf(0.0, 1.0, 2.5, 320.0),
            doubleArrayOf(-40.0, 2.0, 4.0, 90.0),
            doubleArrayOf(60.0, 3.0, 1.5, 250.0),
        )
        for ((translate, scale, nextScale, focal) in cases.map { it.toList() }) {
            val contentPoint = (focal - center - translate) / scale
            val next = ZoomMath.zoomAround(translate, focal, center, nextScale / scale)
            assertEquals(focal, center + next + nextScale * contentPoint, 1e-9)
        }
    }

    @Test
    fun doesNotMoveAnythingWhenZoomingAroundTheCenterFromNoTranslation() {
        assertEquals(0.0, ZoomMath.zoomAround(0.0, 200.0, 200.0, 2.0), 0.0)
    }

    @Test
    fun aPhotoStartsUnzoomed() {
        val zoom = landscape()
        near(1f, zoom.scale)
        near(Offset.Zero, zoom.offset)
        assertFalse(zoom.zoomed)
    }

    @Test
    fun doubleTapZoomsTo2Point5AroundThePointAndAgainResets() {
        val zoom = landscape()
        zoom.doubleTap(Offset(200f, 200f))
        near(2.5f, zoom.scale)
        near(Offset.Zero, zoom.offset)
        assertTrue(zoom.zoomed)
        zoom.doubleTap(Offset(10f, 10f))
        near(1f, zoom.scale)
        near(Offset.Zero, zoom.offset)
        assertFalse(zoom.zoomed)
    }

    @Test
    fun doubleTapNearACornerNeverShowsPastTheImageEdge() {
        val zoom = landscape()
        zoom.doubleTap(Offset(0f, 0f))
        near(2.5f, zoom.scale)
        near(Offset(300f, 175f), zoom.offset)
        zoom.reset()
        zoom.doubleTap(Offset(400f, 400f))
        near(Offset(-300f, -175f), zoom.offset)
    }

    @Test
    fun pinchStaysBetween1And5() {
        val zoom = landscape()
        zoom.pinch(10f, Offset(200f, 200f))
        near(5f, zoom.scale)
        zoom.pinch(0.01f, Offset(200f, 200f))
        near(1f, zoom.scale)
    }

    @Test
    fun aPinchEndingJustAbove1SnapsBack() {
        val zoom = landscape()
        zoom.pinch(1.04f, Offset(200f, 200f))
        assertTrue(zoom.zoomed)
        zoom.endPinch()
        near(1f, zoom.scale)
        near(Offset.Zero, zoom.offset)
        assertFalse(zoom.zoomed)
        zoom.pinch(1.06f, Offset(200f, 200f))
        zoom.endPinch()
        near(1.06f, zoom.scale)
        assertTrue(zoom.zoomed)
    }

    @Test
    fun pinchZoomsAroundTheFingers() {
        val zoom = PhotoZoomState(viewportWidth = 400f, viewportHeight = 400f, imageWidth = 1000f, imageHeight = 1000f)
        zoom.pinch(2f, Offset(300f, 200f))
        near(2f, zoom.scale)
        near(Offset(-100f, 0f), zoom.offset)
    }

    @Test
    fun panningWhileZoomedStopsAtTheImageEdges() {
        val zoom = landscape()
        zoom.pinch(2f, Offset(200f, 200f))
        zoom.pan(Offset(1000f, -1000f))
        near(Offset(200f, -100f), zoom.offset)
        zoom.pan(Offset(-50f, 30f))
        near(Offset(150f, -70f), zoom.offset)
    }

    @Test
    fun panningUnzoomedDoesNothing() {
        val zoom = landscape()
        zoom.pan(Offset(80f, 80f))
        near(Offset.Zero, zoom.offset)
    }

    @Test
    fun aPortraitPhotoCannotMoveSidewaysUntilItOverflows() {
        val zoom = PhotoZoomState(viewportWidth = 400f, viewportHeight = 400f, imageWidth = 2250f, imageHeight = 4000f)
        zoom.pinch(1.5f, Offset(0f, 200f))
        near(0f, zoom.offset.x)
        zoom.pinch(2f / 1.5f, Offset(0f, 200f))
        near(2f, zoom.scale)
        near(25f, zoom.offset.x)
    }

    @Test
    fun resetReturnsToTheFittedPhoto() {
        val zoom = landscape()
        zoom.doubleTap(Offset(0f, 0f))
        zoom.reset()
        near(1f, zoom.scale)
        near(Offset.Zero, zoom.offset)
        assertFalse(zoom.zoomed)
    }

    @Test
    fun playsOnlyTheCurrentPageAndOnlyWhenTheSettingIsOn() {
        val on = AutoplaySettings(autoplayVideos = true, autoplayOnMobileData = false)
        assertTrue(shouldAutoplay(on, AutoplayState(active = true, cached = true, wifi = false)))
        assertFalse(shouldAutoplay(on, AutoplayState(active = false, cached = true, wifi = true)))
        assertFalse(shouldAutoplay(on.copy(autoplayVideos = false), AutoplayState(active = true, cached = true, wifi = true)))
    }

    @Test
    fun downloadsAVideoThatIsNotCachedOnlyOnWifiUnlessMobileDataIsAllowed() {
        val on = AutoplaySettings(autoplayVideos = true, autoplayOnMobileData = false)
        assertTrue(shouldAutoplay(on, AutoplayState(active = true, cached = false, wifi = true)))
        assertFalse(shouldAutoplay(on, AutoplayState(active = true, cached = false, wifi = false)))
        assertTrue(shouldAutoplay(on.copy(autoplayOnMobileData = true), AutoplayState(active = true, cached = false, wifi = false)))
    }

    @Test
    fun buildsTheAndroidGeoUriWithTheCoordinatesAsQuery() {
        assertEquals("geo:48.8584,2.2945?q=48.8584,2.2945", mapUri(48.8584, 2.2945))
    }

    @Test
    fun keepsNegativeCoordinates() {
        assertEquals("geo:-33.8688,-151.2093?q=-33.8688,-151.2093", mapUri(-33.8688, -151.2093))
    }

    @Test
    fun writesWholeCoordinatesLikeJavaScriptNumbers() {
        assertEquals("geo:52,13?q=52,13", mapUri(52.0, 13.0))
        assertEquals("geo:0,-0.5?q=0,-0.5", mapUri(0.0, -0.5))
    }

    @Test
    fun preparationErrorsMapToTheV031Problems() {
        assertEquals(VideoProblem.DAMAGED, videoProblemOf(DecryptionError()))
        assertEquals(VideoProblem.DOWNLOAD_FAILED, videoProblemOf(IOException("reset")))
        assertEquals(VideoProblem.DOWNLOAD_FAILED, videoProblemOf(IllegalStateException("anything else")))
    }

    @Test
    fun playbackErrorsMapByTheirCause() {
        assertEquals(VideoProblem.STOPPED, playbackProblemOf(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(VideoProblem.DAMAGED, playbackProblemOf(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )) {
            assertEquals("code $code", VideoProblem.FORMAT_UNSUPPORTED, playbackProblemOf(code))
        }
    }

    @Test
    fun onlyDownloadFailuresAndStoppedPlaybackOfferRetry() {
        assertEquals(setOf(VideoProblem.DOWNLOAD_FAILED, VideoProblem.STOPPED), VideoProblem.entries.filter { it.retry }.toSet())
    }

    private companion object {
        const val TOLERANCE = 1e-3f
    }
}
