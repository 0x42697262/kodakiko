package dev.kodakiko.app.capture

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.LogValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppDeviceOriginalsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val holder = ActivityHolder()
    private val deletes = PendingDeviceDeletes()
    private val log = RecordingDebugLog()
    private val originals by lazy { AppDeviceOriginals(context, holder, deletes, log, Dispatchers.Main.immediate) }

    @Before
    fun setUp() {
        FakeMediaProvider.calls.clear()
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, DeviceUris.MEDIA)
        imported("a", DeviceUris.image("1"), "m1")
        imported("b", DeviceUris.video("2"), "m2")
    }

    private fun imported(name: String, uri: Uri, mediaId: String) {
        val file = File(context.cacheDir, name)
        deletes.picked(file, uri)
        deletes.imported(file, mediaId)
    }

    private fun logged(outcome: String, count: Int) =
        LoggedEvent(LogLevel.INFO, LogServices.IMPORT, LogEvents.DEVICE_DELETE, mapOf(LogFields.OUTCOME to outcome, LogFields.COUNT to count))

    private fun attachActivity(): FragmentActivity {
        shadowOf(context.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = FragmentActivity::class.java.name
                packageName = context.packageName
            },
        )
        return Robolectric.buildActivity(FragmentActivity::class.java).setup().get().also { holder.attach(it) }
    }

    private fun deleteWithAnswer(activity: FragmentActivity, mediaIds: List<String>, resultCode: Int) {
        var finished = false
        CoroutineScope(Dispatchers.Main.immediate).launch {
            originals.delete(mediaIds)
            finished = true
        }
        shadowOf(activity.mainLooper).idle()
        val request = requireNotNull(shadowOf(activity).lastIntentSenderRequest)
        assertFalse(finished)
        activity.activityResultRegistry.dispatchResult(request.requestCode, resultCode, null)
        shadowOf(activity.mainLooper).idle()
        assertTrue(finished)
    }

    @Test
    fun theDeletableCountIsTheNumberOfMappedOriginals() {
        assertEquals(2, originals.deletableCount(listOf("m1", "m2", "unknown")))
        assertEquals(0, originals.deletableCount(listOf("unknown")))
    }

    @Test
    @Config(sdk = [BELOW_R])
    fun nothingIsDeletableBeforeAndroid11() {
        assertEquals(0, originals.deletableCount(listOf("m1", "m2")))
    }

    @Test
    fun keepForgetsTheOriginalsAndLogsOnlyTheCount() {
        originals.keep(listOf("m1", "m2"))
        assertEquals(0, originals.deletableCount(listOf("m1", "m2")))
        assertEquals(listOf(logged(LogValues.KEPT, 2)), log.events)
    }

    @Test
    fun forgetDropsTheOriginalsWithoutLogging() {
        originals.forget(listOf("m1"))
        assertEquals(1, originals.deletableCount(listOf("m1", "m2")))
        assertEquals(emptyList<LoggedEvent>(), log.events)
    }

    @Test
    fun aReplacedMediaKeepsItsOriginal() {
        originals.replaced("m1", "m1edited")
        assertEquals(0, originals.deletableCount(listOf("m1")))
        assertEquals(1, originals.deletableCount(listOf("m1edited")))
    }

    @Test
    fun deletingWithNothingMappedAsksNothing() = runBlocking {
        originals.delete(listOf("unknown"))
        assertEquals(emptyList<Pair<String, List<Uri>>>(), FakeMediaProvider.calls)
        assertEquals(emptyList<LoggedEvent>(), log.events)
    }

    @Test
    fun deletingWithoutAVisibleActivityFailsAndForgets() = runBlocking {
        originals.delete(listOf("m1", "m2"))
        assertEquals(0, originals.deletableCount(listOf("m1", "m2")))
        assertEquals(emptyList<Pair<String, List<Uri>>>(), FakeMediaProvider.calls)
        assertEquals(listOf(logged(LogValues.FAILED, 2)), log.events)
    }

    @Test
    fun deletingAsksAndroidToDeleteTheOriginalsAndLogsTheConfirmedOutcome() {
        val activity = attachActivity()
        deleteWithAnswer(activity, listOf("m1", "m2"), Activity.RESULT_OK)
        assertEquals(listOf(MEDIA_DELETE_REQUEST to listOf(DeviceUris.image("1"), DeviceUris.video("2"))), FakeMediaProvider.calls)
        assertEquals(0, originals.deletableCount(listOf("m1", "m2")))
        assertEquals(listOf(logged(LogValues.OK, 2)), log.events)
    }

    @Test
    fun aDeclinedSystemConfirmationIsLoggedAsCancelled() {
        val activity = attachActivity()
        deleteWithAnswer(activity, listOf("m2"), Activity.RESULT_CANCELED)
        assertEquals(listOf(MEDIA_DELETE_REQUEST to listOf(DeviceUris.video("2"))), FakeMediaProvider.calls)
        assertEquals(1, originals.deletableCount(listOf("m1", "m2")))
        assertEquals(listOf(logged(LogValues.CANCELLED, 1)), log.events)
    }

    private companion object {
        const val BELOW_R = 28
        const val MEDIA_DELETE_REQUEST = "create_delete_request"
    }
}
