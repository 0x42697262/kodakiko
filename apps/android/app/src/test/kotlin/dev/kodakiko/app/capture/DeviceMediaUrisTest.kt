package dev.kodakiko.app.capture

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceMediaUrisTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, DeviceUris.MEDIA)
    }

    private fun mapped(uri: Uri): Uri? = DeviceMediaUris.mediaStoreUri(context, uri)

    @Test
    fun aLocalPhotoPickerPhotoMapsToItsMediaStoreImage() {
        assertTrue(DeviceMediaUris.supported())
        assertEquals(DeviceUris.image(FakeMediaProvider.PHOTO_ID), mapped(DeviceUris.picker(FakeMediaProvider.PHOTO_ID)))
        assertEquals(DeviceUris.image(FakeMediaProvider.PHOTO_ID), mapped(DeviceUris.picker(FakeMediaProvider.PHOTO_ID, path = "picker_get_content")))
    }

    @Test
    fun aLocalPhotoPickerVideoMapsToItsMediaStoreVideo() {
        assertEquals(DeviceUris.video(FakeMediaProvider.VIDEO_ID), mapped(DeviceUris.picker(FakeMediaProvider.VIDEO_ID)))
    }

    @Test
    fun pickerItemsThatAreNotLocalPhotosOrVideosAreNotMapped() {
        assertNull(mapped(DeviceUris.picker(FakeMediaProvider.PHOTO_ID, provider = DeviceUris.CLOUD_PICKER)))
        assertNull(mapped(DeviceUris.picker(FakeMediaProvider.PDF_ID)))
        assertNull(mapped(DeviceUris.picker("notanumber")))
        assertNull(mapped(DeviceUris.picker("123456")))
    }

    @Test
    fun aMediaStoreItemUriIsKeptAndACollectionUriIsNot() {
        assertEquals(DeviceUris.image("42"), mapped(DeviceUris.image("42")))
        assertNull(mapped(Uri.parse("content://media/external/images/media")))
    }

    @Test
    fun filesAndOtherProvidersAreNotMapped() {
        assertNull(mapped(Uri.parse("file:///sdcard/DCIM/a.jpg")))
        assertNull(mapped(Uri.parse("content://dev.kodakiko.other/files/42")))
    }

    @Test
    @Config(sdk = [BELOW_R])
    fun beforeAndroid11NothingIsMapped() {
        assertFalse(DeviceMediaUris.supported())
        assertNull(mapped(DeviceUris.picker(FakeMediaProvider.PHOTO_ID)))
        assertNull(mapped(DeviceUris.image("42")))
    }

    private companion object {
        const val BELOW_R = 28
    }
}
