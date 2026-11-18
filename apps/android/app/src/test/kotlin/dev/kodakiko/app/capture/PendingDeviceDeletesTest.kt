package dev.kodakiko.app.capture

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingDeviceDeletesTest {
    private val deletes = PendingDeviceDeletes()
    private val a = File("/cache/import-a.jpg")
    private val b = File("/cache/import-b.mp4")
    private val c = File("/cache/import-c.jpg")

    @Test
    fun aPickedFileThatIsImportedMapsItsMediaIdToTheDeviceUri() {
        deletes.picked(a, DeviceUris.image("1"))
        deletes.picked(b, DeviceUris.video("2"))
        deletes.imported(a, "m1")
        deletes.imported(b, "m2")
        assertEquals(listOf(DeviceUris.image("1"), DeviceUris.video("2")), deletes.uris(listOf("m1", "m2")))
        assertEquals(listOf(DeviceUris.video("2")), deletes.uris(listOf("m2", "unknown")))
    }

    @Test
    fun aRefusedImportDropsThePickAndALaterImportOfTheSameFileMapsNothing() {
        deletes.picked(a, DeviceUris.image("1"))
        deletes.imported(a, null)
        deletes.imported(a, "m1")
        assertEquals(emptyList<Uri>(), deletes.uris(listOf("m1")))
    }

    @Test
    fun anImportThatWasNotPickedFromTheDeviceMapsNothing() {
        deletes.imported(c, "m3")
        assertEquals(emptyList<Uri>(), deletes.uris(listOf("m3")))
    }

    @Test
    fun theSameDeviceUriIsListedOnce() {
        deletes.picked(a, DeviceUris.image("1"))
        deletes.picked(c, DeviceUris.image("1"))
        deletes.imported(a, "m1")
        deletes.imported(c, "m3")
        assertEquals(listOf(DeviceUris.image("1")), deletes.uris(listOf("m1", "m3")))
    }

    @Test
    fun aReplacedMediaIdKeepsItsDeviceUriUnderTheNewId() {
        deletes.picked(a, DeviceUris.image("1"))
        deletes.imported(a, "m1")
        deletes.replaced("m1", "m1cropped")
        deletes.replaced("unknown", "other")
        assertEquals(emptyList<Uri>(), deletes.uris(listOf("m1", "other")))
        assertEquals(listOf(DeviceUris.image("1")), deletes.uris(listOf("m1cropped")))
    }

    @Test
    fun forgetDropsOnlyTheGivenMediaIds() {
        deletes.picked(a, DeviceUris.image("1"))
        deletes.picked(b, DeviceUris.video("2"))
        deletes.imported(a, "m1")
        deletes.imported(b, "m2")
        deletes.forget(listOf("m1"))
        assertEquals(listOf(DeviceUris.video("2")), deletes.uris(listOf("m1", "m2")))
    }

    @Test
    fun clearDropsPicksAndMappedMedia() {
        deletes.picked(a, DeviceUris.image("1"))
        deletes.imported(a, "m1")
        deletes.picked(b, DeviceUris.video("2"))
        deletes.clear()
        deletes.imported(b, "m2")
        assertEquals(emptyList<Uri>(), deletes.uris(listOf("m1", "m2")))
    }
}
