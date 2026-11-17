package dev.kodakiko.ui.feature.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContextImpl
import java.util.concurrent.CopyOnWriteArrayList

@Implements(className = ShadowContextImpl.CLASS_NAME, isInAndroidSdk = false)
class RecordingUriGrantsShadow : ShadowContextImpl() {
    @Implementation
    protected fun revokeUriPermission(uri: Uri, modeFlags: Int) {
        revoked += uri to modeFlags
    }

    @Implementation
    protected fun revokeUriPermission(targetPackage: String?, uri: Uri, modeFlags: Int) {
        revoked += uri to modeFlags
    }

    companion object {
        val revoked: MutableList<Pair<Uri, Int>> = CopyOnWriteArrayList()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-mdpi", shadows = [RecordingUriGrantsShadow::class])
class PhoneCameraGrantTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = CaptureHarness()
    private val navigator = RecordingNavigator(listOf(Route.Gallery, Route.Capture()))

    @Before
    fun forgetGrants() {
        RecordingUriGrantsShadow.revoked.clear()
    }

    @After
    fun clearGrants() {
        RecordingUriGrantsShadow.revoked.clear()
    }

    private fun openPhoneCamera(tag: String): Pair<Intent, Uri> {
        h.settings { it.copy(cameraApp = CameraApp.SYSTEM) }
        val vm = CaptureViewModel(h.deps, null, CaptureMode.PHOTO)
        compose.show(preview = FakePreview()) {
            CompositionLocalProvider(LocalRouteNavigator provides navigator) { CaptureScreen(viewModel = vm) }
        }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        val started = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("the phone camera was started", started)
        return started.intent to FileProvider.getUriForFile(context, AUTHORITY, h.captureFiles.outputs.single())
    }

    private fun deliver(request: Intent, resultCode: Int) {
        compose.runOnUiThread { shadowOf(compose.activity).receiveResult(request, resultCode, null) }
        compose.waitForIdle()
    }

    private fun revokedReadAndWrite(uri: Uri): Boolean =
        RecordingUriGrantsShadow.revoked.any { (revokedUri, flags) ->
            revokedUri == uri && flags and READ_WRITE == READ_WRITE
        }

    @Test
    fun aPhotoResultRevokesTheReadAndWriteGrantOnTheOutputUri() {
        val (request, uri) = openPhoneCamera(TestTags.Capture.SYSTEM_PHOTO)
        assertEquals(emptyList<Pair<Uri, Int>>(), RecordingUriGrantsShadow.revoked.toList())

        deliver(request, Activity.RESULT_OK)

        assertTrue("revoked: ${RecordingUriGrantsShadow.revoked}", revokedReadAndWrite(uri))
    }

    @Test
    fun aCancelledVideoRevokesTheGrantToo() {
        val (request, uri) = openPhoneCamera(TestTags.Capture.SYSTEM_VIDEO)

        deliver(request, Activity.RESULT_CANCELED)

        assertTrue("revoked: ${RecordingUriGrantsShadow.revoked}", revokedReadAndWrite(uri))
    }

    private companion object {
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
