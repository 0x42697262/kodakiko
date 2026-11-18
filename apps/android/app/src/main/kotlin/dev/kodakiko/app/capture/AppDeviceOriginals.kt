package dev.kodakiko.app.capture

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.LogValues
import dev.kodakiko.ui.feature.edit.DeviceOriginals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class AppDeviceOriginals(
    private val context: Context,
    private val holder: ActivityHolder,
    private val deletes: PendingDeviceDeletes,
    private val log: DebugLog,
    private val ioDispatcher: CoroutineDispatcher,
) : DeviceOriginals {
    private val counter = AtomicInteger()

    override fun deletableCount(mediaIds: Collection<String>): Int = if (DeviceMediaUris.supported()) deletes.uris(mediaIds).size else 0

    override fun replaced(oldMediaId: String, newMediaId: String) = deletes.replaced(oldMediaId, newMediaId)

    override fun keep(mediaIds: Collection<String>) {
        val count = deletes.uris(mediaIds).size
        deletes.forget(mediaIds)
        record(LogValues.KEPT, count)
    }

    override suspend fun delete(mediaIds: Collection<String>) {
        val uris = deletes.uris(mediaIds)
        deletes.forget(mediaIds)
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val outcome = try {
            request(uris)
        } catch (cancelled: CancellationException) {
            record(LogValues.CANCELLED, uris.size)
            throw cancelled
        } catch (_: RuntimeException) {
            LogValues.FAILED
        }
        record(outcome, uris.size)
    }

    override fun forget(mediaIds: Collection<String>) = deletes.forget(mediaIds)

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun request(uris: List<Uri>): String {
        val activity = holder.current ?: return LogValues.FAILED
        val pending = withContext(ioDispatcher) { MediaStore.createDeleteRequest(context.contentResolver, uris) }
        return withContext(Dispatchers.Main.immediate) { awaitResult(activity, pending.intentSender) }
    }

    private suspend fun awaitResult(activity: ComponentActivity, sender: IntentSender): String =
        suspendCancellableCoroutine { continuation ->
            lateinit var launcher: ActivityResultLauncher<IntentSenderRequest>
            launcher = activity.activityResultRegistry.register(
                CaptureAdapterConstants.DEVICE_DELETE_KEY_PREFIX + counter.incrementAndGet(),
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                launcher.unregister()
                val outcome = if (result.resultCode == Activity.RESULT_OK) LogValues.OK else LogValues.CANCELLED
                if (continuation.isActive) continuation.resume(outcome)
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        }

    private fun record(outcome: String, count: Int) {
        log.record(LogLevel.INFO, LogServices.IMPORT, LogEvents.DEVICE_DELETE, mapOf(LogFields.OUTCOME to outcome, LogFields.COUNT to count))
    }
}
