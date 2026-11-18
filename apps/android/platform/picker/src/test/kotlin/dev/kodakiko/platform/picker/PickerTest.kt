package dev.kodakiko.platform.picker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PickerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val authority = "dev.kodakiko.test.files"

    class FakeFiles : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            if (uri.lastPathSegment == "unnamed.bin") return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>("Holiday ${uri.lastPathSegment}", 5))
            }
        }

        override fun getType(uri: Uri): String? = if (uri.lastPathSegment!!.endsWith(".mp4")) "video/mp4" else "image/jpeg"

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (uri.lastPathSegment == "missing.jpg") throw java.io.FileNotFoundException()
            val file = File.createTempFile("fake", ".bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5))
                deleteOnExit()
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Test
    fun mediaPickingUsesThePhotoPickerWithTheRemainingSlots() {
        val many = MediaPicking.request(remainingSlots = 7)!!
        val manyIntent = many.contract.createIntent(context, many.input)
        assertEquals(MediaStore.ACTION_PICK_IMAGES, manyIntent.action)
        assertEquals(7, manyIntent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1))
        val one = MediaPicking.request(remainingSlots = 1)!!
        val oneIntent = one.contract.createIntent(context, one.input)
        assertEquals(MediaStore.ACTION_PICK_IMAGES, oneIntent.action)
        assertTrue(!oneIntent.hasExtra(MediaStore.EXTRA_PICK_IMAGES_MAX))
        assertNull(MediaPicking.request(remainingSlots = 0))
        val capped = MediaPicking.request(remainingSlots = 500)!!
        assertEquals(MediaStore.getPickImagesMaxLimit(), capped.contract.createIntent(context, capped.input).getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1))
        assertEquals(ActivityResultContracts.PickVisualMedia.ImageAndVideo, (many.input).mediaType)
    }

    @Test
    fun phoneCameraIntentsWriteToAFileProviderUri() {
        val output = File(context.cacheDir, "capture/photo.jpg").apply { parentFile!!.mkdirs() }
        val photo = PhoneCamera.photoIntent(context, authority, output)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, photo.action)
        val uri = photo.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        assertTrue(photo.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(photo.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, photo.clipData!!.getItemAt(0).uri)
        val video = PhoneCamera.videoIntent(context, authority, File(context.cacheDir, "capture/video.mp4"))
        assertEquals(MediaStore.ACTION_VIDEO_CAPTURE, video.action)
        assertEquals(authority, video.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)!!.authority)
    }

    @Test
    fun pickedUrisAreCopiedWithTheirNameSizeAndType() {
        Robolectric.setupContentProvider(FakeFiles::class.java, "dev.kodakiko.fake")
        val destination = File(context.cacheDir, "import").apply { mkdirs() }
        val copier = UriCopier(context)
        val photo = copier.copy(Uri.parse("content://dev.kodakiko.fake/a.jpg"), File(destination, "one"))!!
        assertEquals(PickedFile(File(destination, "one"), "Holiday a.jpg", 5, "image/jpeg"), photo)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), photo.file.readBytes())
        val video = copier.copy(Uri.parse("content://dev.kodakiko.fake/b.mp4"), File(destination, "two"))!!
        assertEquals("video/mp4", video.mimeType)
        val unnamed = copier.copy(Uri.parse("content://dev.kodakiko.fake/unnamed.bin"), File(destination, "three"))!!
        assertNull(unnamed.displayName)
        assertNull(copier.copy(Uri.parse("content://dev.kodakiko.fake/missing.jpg"), File(destination, "four")))
        assertTrue(!File(destination, "four").exists())
    }

    @Test
    fun documentsAreOpenedManyAtOnceAndFoldersByTree() {
        val files = DocumentPicking.openMany()
        val filesIntent = files.contract.createIntent(context, files.input)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, filesIntent.action)
        assertTrue(filesIntent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val folder = DocumentPicking.openFolder()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, folder.contract.createIntent(context, folder.input).action)
    }

    @Test
    fun sharingOneFileSendsItWithItsTypeAndManyFilesSendMultiple() {
        val first = File(context.cacheDir, "share/a.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val second = File(context.cacheDir, "share/b.mp4").apply { writeBytes(byteArrayOf(2)) }
        val single = Sharing.chooser(context, authority, listOf(SharedFile(first, "a.jpg", "image/jpeg")), text = null)
        assertEquals(Intent.ACTION_CHOOSER, single.action)
        val inner = single.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("image/jpeg", inner.type)
        assertEquals(authority, inner.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!.authority)
        assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull(inner.getStringExtra(Intent.EXTRA_TEXT))

        val many = Sharing.chooser(context, authority, listOf(SharedFile(first, "a.jpg", "image/jpeg"), SharedFile(second, "b.mp4", "video/mp4")), text = "From Kodakiko")
        val manyInner = many.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, manyInner.action)
        assertEquals("*/*", manyInner.type)
        assertEquals(2, manyInner.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)!!.size)
        assertEquals(2, manyInner.clipData!!.itemCount)
        assertEquals("From Kodakiko", manyInner.getStringExtra(Intent.EXTRA_TEXT))
    }
}
