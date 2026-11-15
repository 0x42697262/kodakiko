package dev.kodakiko.service.links

import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.media.STRIP_COPY_CHUNK_BYTES
import dev.kodakiko.media.UnsupportedVideoError
import dev.kodakiko.media.byteSourceFromBytes
import dev.kodakiko.media.hasExifSegment
import dev.kodakiko.media.planWebmDateStrip
import dev.kodakiko.media.readVideoMetadata
import dev.kodakiko.media.streamStripped
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

private val SOURCE_MEDIA_ID = "A".repeat(22)

private fun descriptorOf(kind: MediaKind, mimeType: String, plaintextBytes: Int, durationMs: Long? = null): MediaDescriptorV1 =
    MediaDescriptorV1(
        mediaId = SOURCE_MEDIA_ID,
        kind = kind,
        mimeType = mimeType,
        width = 640,
        height = 480,
        durationMs = durationMs,
        originalPlaintextBytes = plaintextBytes.toLong(),
        thumbnail = ThumbnailDescriptor(width = 1, height = 1, plaintextBytes = 64),
        capturedAtUtc = "2026-05-01T08:00:00.000Z",
        originalFileName = "clip",
    )

private fun StrippedMedia.bytes(): ByteArray = concatAll(chunks.toList())

class LinkStripperTest {
    @Test
    fun aPhotoIsReEncodedAsJpegWithTheEncoderSize() = runTest {
        val original = fakeJpeg(5000, 3)
        val encoded = buildJpeg(listOf(jfifApp0()), 64)
        val received = CopyOnWriteArrayList<Pair<ByteArray, String>>()
        val linkStripper: LinkStripper = DefaultLinkStripper(
            PhotoReencoder { bytes: ByteArray, mimeType: String ->
                received += bytes to mimeType
                ReencodedPhoto(encoded, 1600, 1200)
            },
        )
        val stripped = linkStripper.strip(byteSourceFromBytes(original), descriptorOf(MediaKind.PHOTO, "image/png", original.size))
        assertEquals(1, received.size)
        assertArrayEquals(original, received[0].first)
        assertEquals("image/png", received[0].second)
        assertEquals("image/jpeg", stripped.mimeType)
        assertEquals(1600L, stripped.width)
        assertEquals(1200L, stripped.height)
        assertNull(stripped.durationMs)
        assertEquals(encoded.size.toLong(), stripped.plaintextSize)
        assertArrayEquals(encoded, stripped.bytes())
    }

    @Test
    fun aPhotoTheEncoderCannotReadIsUndecodable() = runTest {
        val linkStripper: LinkStripper = DefaultLinkStripper(PhotoReencoder { _: ByteArray, _: String -> throw IOException("undecodable") })
        val original = fakeJpeg(100, 1)
        expectFailure<StripUndecodableException> { linkStripper.strip(byteSourceFromBytes(original), descriptorOf(MediaKind.PHOTO, "image/jpeg", original.size)) }
    }

    @Test
    fun anEncodedPhotoThatStillCarriesExifFailsVerification() = runTest {
        val original = fakeJpeg(100, 1)
        val withOrientationOnly = buildJpeg(listOf(exifApp1(buildTiff(orientation = 1))), 16)
        val withGps = buildJpeg(listOf(exifApp1(buildTiff(gps = PARIS))), 16)
        val withDate = buildJpeg(listOf(exifApp1(buildTiff(dateTimeOriginal = "2026:05:01 10:00:00"))), 16)
        assertTrue(hasExifSegment(withOrientationOnly))
        for (output in listOf(withOrientationOnly, withGps, withDate)) {
            val linkStripper: LinkStripper = DefaultLinkStripper(PhotoReencoder { _: ByteArray, _: String -> ReencodedPhoto(output, 10, 10) })
            expectFailure<StripFailedException> { linkStripper.strip(byteSourceFromBytes(original), descriptorOf(MediaKind.PHOTO, "image/jpeg", original.size)) }
        }
    }

    @Test
    fun anMp4LosesItsLocationAndCreationTimeAndKeepsTheDescriptorFacts() = runTest {
        val video = mp4WithLocation()
        val linkStripper: LinkStripper = DefaultLinkStripper(passThroughReencoder())
        val stripped = linkStripper.strip(byteSourceFromBytes(video), descriptorOf(MediaKind.VIDEO, "video/mp4", video.size, 2000))
        val output = stripped.bytes()
        assertEquals("video/mp4", stripped.mimeType)
        assertEquals(640L, stripped.width)
        assertEquals(480L, stripped.height)
        assertEquals(2000L, stripped.durationMs)
        assertEquals(output.size.toLong(), stripped.plaintextSize)
        assertTrue(output.size < video.size)
        val check = readVideoMetadata(byteSourceFromBytes(output))
        assertNull(check.creationUtc)
        assertNull(check.location)
        assertArrayEquals(video.copyOfRange(video.size - 2048, video.size), output.copyOfRange(output.size - 2048, output.size))
    }

    @Test
    fun anMp4WithALocationBoxDirectlyInMoovFailsVerification() = runTest {
        val video = mp4WithLocationDirectlyInMoov()
        val linkStripper: LinkStripper = DefaultLinkStripper(passThroughReencoder())
        expectFailure<StripFailedException> { linkStripper.strip(byteSourceFromBytes(video), descriptorOf(MediaKind.VIDEO, "video/mp4", video.size, 2000)) }
    }

    @Test
    fun aFragmentedMp4IsAnUnsupportedVideo() = runTest {
        val video = fragmentedMp4()
        val linkStripper: LinkStripper = DefaultLinkStripper(passThroughReencoder())
        expectFailure<UnsupportedVideoError> { linkStripper.strip(byteSourceFromBytes(video), descriptorOf(MediaKind.VIDEO, "video/mp4", video.size, 2000)) }
    }

    @Test
    fun aWebmKeepsItsSizeAndOnlyLosesItsDate() = runTest {
        val video = fixtureMedia("video-short.webm").readBytes()
        val source = byteSourceFromBytes(video)
        val linkStripper: LinkStripper = DefaultLinkStripper(passThroughReencoder())
        val stripped = linkStripper.strip(source, descriptorOf(MediaKind.VIDEO, "video/webm", video.size, 2000))
        val output = stripped.bytes()
        assertEquals("video/webm", stripped.mimeType)
        assertEquals(2000L, stripped.durationMs)
        assertEquals(video.size.toLong(), stripped.plaintextSize)
        assertEquals(video.size, output.size)
        assertArrayEquals(concatAll(streamStripped(source, planWebmDateStrip(source).segments, STRIP_COPY_CHUNK_BYTES).toList()), output)
    }
}
