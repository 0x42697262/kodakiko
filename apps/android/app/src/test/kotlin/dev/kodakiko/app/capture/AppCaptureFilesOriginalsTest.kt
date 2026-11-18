package dev.kodakiko.app.capture

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.service.entries.MediaOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppCaptureFilesOriginalsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val deletes = PendingDeviceDeletes()
    private val log = RecordingDebugLog()
    private val files by lazy { AppCaptureFiles(context, TempFiles(context, createSodium()) { ACCOUNT_HASH }, deletes, log, Dispatchers.Unconfined) }

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, DeviceUris.MEDIA)
    }

    private fun unmapped() = LoggedEvent(LogLevel.DEBUG, LogServices.IMPORT, LogEvents.DEVICE_ORIGINAL_UNMAPPED, mapOf(LogFields.COUNT to 1))

    @Test
    fun aPickedPhotoIsCopiedAndItsDeviceOriginalIsRememberedForTheImportedMedia() = runBlocking {
        val input = requireNotNull(files.copyPicked(DeviceUris.picker(FakeMediaProvider.PHOTO_ID)))
        assertEquals(MediaOrigin.PICKER, input.origin)
        assertArrayEquals(FakeMediaProvider.BYTES, input.file.readBytes())
        assertEquals(emptyList<Uri>(), deletes.uris(listOf("m1")))
        files.imported(input, "m1")
        assertEquals(listOf(DeviceUris.image(FakeMediaProvider.PHOTO_ID)), deletes.uris(listOf("m1")))
        assertEquals(emptyList<LoggedEvent>(), log.events)
    }

    @Test
    fun aRefusedImportRemembersNothing() = runBlocking {
        val input = requireNotNull(files.copyPicked(DeviceUris.picker(FakeMediaProvider.VIDEO_ID)))
        files.imported(input, null)
        files.imported(input, "m2")
        assertEquals(emptyList<Uri>(), deletes.uris(listOf("m2")))
    }

    @Test
    fun forgetDropsTheRememberedOriginal() = runBlocking {
        val photo = requireNotNull(files.copyPicked(DeviceUris.picker(FakeMediaProvider.PHOTO_ID)))
        val video = requireNotNull(files.copyPicked(DeviceUris.picker(FakeMediaProvider.VIDEO_ID)))
        files.imported(photo, "m1")
        files.imported(video, "m2")
        files.forget(listOf("m1"))
        assertEquals(listOf(DeviceUris.video(FakeMediaProvider.VIDEO_ID)), deletes.uris(listOf("m1", "m2")))
    }

    @Test
    fun anUnmappablePickIsStillCopiedAndOnlyItsCountIsLogged() = runBlocking {
        val input = requireNotNull(files.copyPicked(DeviceUris.picker(FakeMediaProvider.PHOTO_ID, provider = DeviceUris.CLOUD_PICKER)))
        assertArrayEquals(FakeMediaProvider.BYTES, input.file.readBytes())
        files.imported(input, "m3")
        assertEquals(emptyList<Uri>(), deletes.uris(listOf("m3")))
        assertEquals(listOf(unmapped()), log.events)
    }

    private companion object {
        const val ACCOUNT_HASH = "b1abbb91a5439ec1"
    }
}
