package dev.kodakiko.platform.picker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class PickerFilePathsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sharedPathsFileExposesOnlyTheCaptureAndShareCacheFolders() {
        val parser = context.resources.getXml(R.xml.picker_file_paths)
        val entries = mutableListOf<Pair<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name != "paths") {
                entries += parser.name to parser.getAttributeValue(null, "path")
            }
        }
        assertEquals(
            listOf("cache-path" to "${PickerConstants.CAPTURE_DIRECTORY}/", "cache-path" to "${PickerConstants.SHARE_DIRECTORY}/"),
            entries,
        )
    }
}
