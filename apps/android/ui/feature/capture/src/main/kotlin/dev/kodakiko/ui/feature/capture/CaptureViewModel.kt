package dev.kodakiko.ui.feature.capture

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.CameraSide
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.platform.camera.CameraConstants
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.platform.camera.ShutterAction
import dev.kodakiko.platform.camera.ShutterEvent
import dev.kodakiko.platform.camera.shutterAction
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.entries.EntriesConstants
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.ImportedMedia
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.service.entries.maxPlaintextBytes
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import dev.kodakiko.platform.camera.CaptureMode as ShutterMode

class CaptureViewModel(private val deps: CaptureDeps, private val entryId: String?, mode: CaptureMode) : ViewModel() {
    private val eventChannel = Channel<CaptureEvent>(Channel.UNLIMITED)
    private val importLock = Mutex()
    private val thumbnails = HashMap<String, ImageBitmap?>()
    private val entryMediaCount = entryId?.let { id -> deps.index.index()?.get(id)?.mediaCount } ?: 0
    private val current = MutableStateFlow(initialState(mode))
    private var holdJob: Job? = null
    private var holding = false
    private var capJob: Job? = null
    private var tickerJob: Job? = null
    private var starting = false
    private var recordingActive = false
    private var locationRequested = false
    private var phoneOutput: File? = null
    private val unsubscribe: () -> Unit

    val events: Flow<CaptureEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<CaptureState> = current.asStateFlow()

    init {
        checkEntry()
        unsubscribe = deps.tray.subscribe { current.update { it.copy(items = trayItems()) } }
        viewModelScope.launch {
            deps.settings.collect { settings -> current.update { it.copy(cameraApp = settings.cameraApp, keepScreenOn = settings.captureKeepScreenOn) } }
        }
    }

    fun onAction(action: CaptureAction) {
        when (action) {
            CaptureAction.ShutterDown -> shutterDown()
            CaptureAction.ShutterUp -> shutterUp()
            CaptureAction.AllowCamera -> allowCamera()
            CaptureAction.OpenSettings -> send(CaptureEvent.OpenAppSettings)
            CaptureAction.Resumed -> current.update { it.copy(permission = deps.permissions.camera()) }
            CaptureAction.Flip -> unlessRecording { current.update { it.copy(facing = flipped(it.facing)) } }
            is CaptureAction.SetMode -> unlessRecording { current.update { it.copy(mode = action.mode) } }
            CaptureAction.PickFromGallery -> unlessRecording { send(CaptureEvent.PickFromGallery(remainingSlots())) }
            is CaptureAction.Picked -> picked(action)
            is CaptureAction.Remove -> viewModelScope.launch {
                deps.tray.remove(action.mediaId)
                deps.files.forget(listOf(action.mediaId))
            }
            is CaptureAction.PhoneCamera -> openPhoneCamera(action.video)
            is CaptureAction.PhoneCameraResult -> phoneCameraResult(action.ok)
            CaptureAction.Next -> if (current.value.canNext) send(CaptureEvent.Open(Route.EntryNew))
            CaptureAction.AddToEntry -> addToEntry()
            CaptureAction.Close -> close()
            CaptureAction.Keep -> current.update { if (it.dialog is CaptureDialog.Discard) it.copy(dialog = null) else it }
            CaptureAction.Discard -> discard()
            CaptureAction.CancelAdd -> send(CaptureEvent.Back)
        }
    }

    override fun onCleared() {
        unsubscribe()
        if (recordingActive) deps.camera.stopRecording()
        super.onCleared()
    }

    private fun initialState(mode: CaptureMode): CaptureState {
        val settings = deps.settings.value
        val adding = entryId != null
        val items = trayItems()
        return CaptureState(
            addToEntry = adding,
            cameraApp = settings.cameraApp,
            permission = deps.permissions.camera(),
            mode = if (adding) mode else CaptureMode.PHOTO,
            facing = if (settings.captureStartCamera == CameraSide.FRONT) CameraFacing.FRONT else CameraFacing.BACK,
            recording = null,
            items = items,
            pending = 0,
            refusals = emptyList(),
            dialog = if (adding && items.isNotEmpty()) CaptureDialog.Leftover(items.size) else null,
            adding = false,
            keepScreenOn = settings.captureKeepScreenOn,
        )
    }

    private fun checkEntry() {
        val id = entryId ?: return
        val entry = deps.index.index()?.get(id)
        if (entry == null || !entry.isOwn || entry.trashed) send(CaptureEvent.Replace(Route.Entry(id)))
    }

    private fun send(event: CaptureEvent) {
        eventChannel.trySend(event)
    }

    private fun settings(): DeviceSettings = deps.settings.value

    private fun recording(): Boolean = recordingActive || starting

    private inline fun unlessRecording(block: () -> Unit) {
        if (!recording()) block()
    }

    private fun flipped(facing: CameraFacing): CameraFacing = if (facing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK

    private fun trayItems(): List<CaptureTrayItem> {
        val items = deps.tray.items
        val ids = items.mapTo(HashSet()) { it.mediaId }
        synchronized(thumbnails) { thumbnails.keys.retainAll(ids) }
        return items.map { CaptureTrayItem(it.mediaId, thumbnail(it)) }
    }

    private fun thumbnail(item: ImportedMedia): ImageBitmap? = synchronized(thumbnails) {
        if (item.mediaId in thumbnails) return thumbnails[item.mediaId]
        val decoded = try {
            BitmapFactory.decodeByteArray(item.thumbnailBytes, 0, item.thumbnailBytes.size)?.asImageBitmap()
        } catch (_: RuntimeException) {
            null
        }
        thumbnails[item.mediaId] = decoded
        decoded
    }

    private fun limit(): Int {
        val configured = (deps.config.current()?.maxMediaPerEntry ?: EntriesConstants.FALLBACK_MAX_MEDIA_PER_ENTRY).toInt()
        return if (entryId == null) configured else (configured - entryMediaCount).coerceAtLeast(0)
    }

    private fun remainingSlots(): Int = (limit() - deps.tray.items.size - current.value.pending).coerceAtLeast(0)

    private fun recordingCap(): Long = maxPlaintextBytes(deps.config.current()?.maxUploadBytes ?: EntriesConstants.FALLBACK_MAX_UPLOAD_BYTES)

    private fun shutterDown() {
        holdJob?.cancel()
        holding = false
        holdJob = viewModelScope.launch {
            delay(CameraConstants.HOLD_TO_RECORD_MS)
            holdJob = null
            holding = true
            shutter(ShutterEvent.HOLD_START)
        }
    }

    private fun shutterUp() {
        val pendingHold = holdJob
        when {
            pendingHold != null -> {
                pendingHold.cancel()
                holdJob = null
                shutter(ShutterEvent.TAP)
            }
            holding -> {
                holding = false
                shutter(ShutterEvent.HOLD_END)
            }
        }
    }

    private fun shutter(event: ShutterEvent) {
        val mode = if (current.value.mode == CaptureMode.VIDEO) ShutterMode.VIDEO else ShutterMode.PHOTO
        when (shutterAction(mode, event, settings().holdToRecordOn, recording(), capJob != null)) {
            ShutterAction.PHOTO -> {
                deps.haptics(HapticKind.SHUTTER)
                takePhoto()
            }
            ShutterAction.START -> {
                deps.haptics(HapticKind.RECORD_START)
                startRecording(capped = false)
            }
            ShutterAction.START_CAPPED -> {
                deps.haptics(HapticKind.RECORD_START)
                startRecording(capped = true)
            }
            ShutterAction.STOP -> {
                capJob?.cancel()
                capJob = null
                deps.haptics(HapticKind.RECORD_STOP)
                deps.camera.stopRecording()
            }
            ShutterAction.NONE -> Unit
        }
    }

    private fun takePhoto() {
        viewModelScope.launch {
            val input = attempt { deps.camera.takePhoto() } ?: return@launch
            addInputs(listOf(input))
        }
    }

    private fun startRecording(capped: Boolean) {
        starting = true
        viewModelScope.launch {
            val allowed = try {
                deps.permissions.microphone() || attempt { deps.permissions.requestMicrophone() } == true
            } finally {
                starting = false
            }
            if (!allowed) return@launch
            beginRecording(capped)
        }
    }

    private fun beginRecording(capped: Boolean) {
        val startedAt = deps.clock.nowMs()
        recordingActive = true
        current.update { it.copy(recording = CaptureRecording(elapsedMs = 0)) }
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(CaptureConstants.RECORDING_TIMER_INTERVAL_MS)
                val elapsed = deps.clock.nowMs() - startedAt
                current.update { if (it.recording == null) it else it.copy(recording = CaptureRecording(elapsedMs = elapsed)) }
            }
        }
        if (capped) {
            capJob = viewModelScope.launch {
                delay(settings().holdToRecordMaxSeconds * CaptureConstants.MS_PER_SECOND)
                capJob = null
                deps.camera.stopRecording()
            }
        }
        deps.camera.startRecording(recordingCap(), ::recordingFinished)
    }

    private fun recordingFinished(input: MediaInput?) {
        recordingActive = false
        tickerJob?.cancel()
        tickerJob = null
        capJob?.cancel()
        capJob = null
        current.update { it.copy(recording = null) }
        if (input != null) addInputs(listOf(input))
    }

    private fun beginBatch(count: Int) {
        current.update { it.copy(refusals = emptyList(), pending = it.pending + count) }
    }

    private fun addInputs(inputs: List<MediaInput>) {
        if (inputs.isEmpty()) return
        beginBatch(inputs.size)
        if (inputs.any { it.origin == MediaOrigin.CAMERA }) requestLocationOnce()
        queueImports(inputs, deps.tray.generation)
    }

    private fun queueImports(inputs: List<MediaInput>, generation: Int) {
        inputs.forEach { input ->
            viewModelScope.launch { importLock.withLock { importOne(input, generation) } }
        }
    }

    private suspend fun importOne(input: MediaInput, generation: Int) {
        try {
            val limit = limit()
            val outcome = attempt { deps.imports.importOne(input, ImportSlots(used = deps.tray.items.size, limit = limit)) }
                ?: ImportOutcome.Refused(ImportRefusal.READ_FAILED, input.fileName)
            deps.files.imported(input, (outcome as? ImportOutcome.Imported)?.media?.mediaId)
            when (outcome) {
                is ImportOutcome.Imported -> deps.tray.addImported(outcome.media, generation)
                is ImportOutcome.Refused -> addRefusal(CaptureRefusal(outcome.reason, refusalLabel(input), limit))
            }
        } finally {
            current.update { it.copy(pending = (it.pending - 1).coerceAtLeast(0)) }
        }
    }

    private fun addRefusal(refusal: CaptureRefusal) {
        current.update { state ->
            if (state.refusals.any { it.sameText(refusal) }) state else state.copy(refusals = state.refusals + refusal)
        }
    }

    private fun picked(action: CaptureAction.Picked) {
        val uris = action.uris
        if (uris.isEmpty()) return
        beginBatch(uris.size)
        val generation = deps.tray.generation
        viewModelScope.launch {
            val inputs = uris.mapNotNull { uri -> attempt { deps.files.copyPicked(uri) } }
            val skipped = uris.size - inputs.size
            if (skipped > 0) current.update { it.copy(pending = (it.pending - skipped).coerceAtLeast(0)) }
            queueImports(inputs, generation)
        }
    }

    private fun requestLocationOnce() {
        if (locationRequested || !settings().locationForCaptures) return
        locationRequested = true
        viewModelScope.launch {
            val location = attempt { deps.location.currentPosition(CaptureConstants.LOCATION_TIMEOUT_MS) } ?: return@launch
            deps.tray.setDeviceLocation(location)
        }
    }

    private fun allowCamera() {
        viewModelScope.launch {
            val permission = attempt { deps.permissions.requestCamera() } ?: return@launch
            current.update { it.copy(permission = permission) }
        }
    }

    private fun openPhoneCamera(video: Boolean) {
        if (recording() || remainingSlots() <= 0) return
        val output = try {
            deps.files.phoneCameraOutput(video)
        } catch (_: Exception) {
            return
        }
        phoneOutput?.delete()
        phoneOutput = output
        send(CaptureEvent.OpenPhoneCamera(video = video, output = output, authority = deps.fileProviderAuthority))
    }

    private fun phoneCameraResult(ok: Boolean) {
        val output = phoneOutput ?: return
        phoneOutput = null
        if (ok) addInputs(listOf(MediaInput(MediaOrigin.PICKER, output, null, null))) else output.delete()
    }

    private fun addToEntry() {
        val id = entryId ?: return
        val state = current.value
        if (!state.canAddToEntry || state.adding) return
        current.update { it.copy(adding = true) }
        viewModelScope.launch {
            val added = attempt {
                deps.entries.addMedia(id, deps.tray.items)
                deps.tray.clear(false)
                true
            } == true
            if (added) send(CaptureEvent.Back) else current.update { it.copy(adding = false) }
        }
    }

    private fun close() {
        val state = current.value
        if (state.dialog is CaptureDialog.Leftover) return
        if (state.items.isEmpty() && state.pending == 0) {
            send(CaptureEvent.Back)
        } else {
            current.update { it.copy(dialog = CaptureDialog.Discard(count = it.items.size)) }
        }
    }

    private fun discard() {
        when (current.value.dialog) {
            is CaptureDialog.Discard -> viewModelScope.launch {
                clearTray()
                current.update { it.copy(dialog = null) }
                send(CaptureEvent.Back)
            }
            is CaptureDialog.Leftover -> viewModelScope.launch {
                clearTray()
                current.update { it.copy(dialog = null) }
            }
            null -> Unit
        }
    }

    private suspend fun clearTray() {
        deps.files.forget(deps.tray.items.map { it.mediaId })
        deps.tray.clear(true)
    }

    private suspend fun <T> attempt(work: suspend () -> T): T? = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
