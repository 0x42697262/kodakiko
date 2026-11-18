@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.capture

import android.net.Uri
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.data.db.CameraSide
import dev.kodakiko.platform.camera.CameraConstants
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.service.entries.maxPlaintextBytes
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = CaptureHarness()

    private val cap = maxPlaintextBytes(MAX_UPLOAD_BYTES)

    private fun TestScope.open(entryId: String? = null, mode: CaptureMode = CaptureMode.PHOTO): Pair<CaptureViewModel, Observed<CaptureState, CaptureEvent>> {
        h.clock.source = { testScheduler.currentTime }
        val vm = CaptureViewModel(h.deps, entryId, mode)
        clearAtEnd(vm)
        return vm to observe(vm.state, vm.events)
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.tap(vm: CaptureViewModel) {
        vm.onAction(CaptureAction.ShutterDown)
        advance(TAP_MS)
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
    }

    private fun TestScope.holdStart(vm: CaptureViewModel) {
        vm.onAction(CaptureAction.ShutterDown)
        advance(CameraConstants.HOLD_TO_RECORD_MS)
    }

    private fun texts(state: CaptureState): List<String> = state.refusals.map { refusalText(resources, it) }

    private fun ids(state: CaptureState): List<String> = state.items.map { it.mediaId }

    private fun uri(name: String): Uri = Uri.parse("content://media/picker/0/$name")

    @Test
    fun aNewEntryOpensInPhotoModeOnTheStartCamera() = runTest {
        val (_, screen) = open()
        val state = screen.value
        assertFalse(state.addToEntry)
        assertEquals(CameraApp.KODAKIKO, state.cameraApp)
        assertEquals(CameraPermission.GRANTED, state.permission)
        assertEquals(CaptureMode.PHOTO, state.mode)
        assertEquals(CameraFacing.BACK, state.facing)
        assertNull(state.recording)
        assertEquals(emptyList<CaptureTrayItem>(), state.items)
        assertEquals(0, state.pending)
        assertEquals(emptyList<CaptureRefusal>(), state.refusals)
        assertNull(state.dialog)
        assertFalse(state.adding)
        assertTrue(state.keepScreenOn)
        assertEquals(emptyList<CaptureEvent>(), screen.events)
    }

    @Test
    fun settingsChooseTheFirstCameraTheScreenOnRuleAndThePhoneCameraApp() = runTest {
        h.settings { it.copy(captureStartCamera = CameraSide.FRONT, captureKeepScreenOn = false, cameraApp = CameraApp.SYSTEM) }
        val (_, screen) = open()
        assertEquals(CameraFacing.FRONT, screen.value.facing)
        assertFalse(screen.value.keepScreenOn)
        assertEquals(CameraApp.SYSTEM, screen.value.cameraApp)
    }

    @Test
    fun theModeFromTheRouteAppliesWhenAddingToAnEntryOnly() = runTest {
        h.entry(mediaCount = 2)
        val (_, adding) = open(entryId = ENTRY, mode = CaptureMode.VIDEO)
        assertTrue(adding.value.addToEntry)
        assertEquals(CaptureMode.VIDEO, adding.value.mode)
        val (_, fresh) = open(entryId = null, mode = CaptureMode.VIDEO)
        assertEquals(CaptureMode.PHOTO, fresh.value.mode)
    }

    @Test
    fun anEntryThatIsNotOwnOrIsTrashedSendsBackToTheEntry() = runTest {
        h.entry(mediaCount = 1, isOwn = false)
        val (_, shared) = open(entryId = ENTRY)
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Replace(Route.Entry(ENTRY))), shared.events)
        h.entry(mediaCount = 1, trashed = true)
        val (_, trashed) = open(entryId = ENTRY)
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Replace(Route.Entry(ENTRY))), trashed.events)
    }

    @Test
    fun allowCameraAsksForThePermissionAndShowsTheResult() = runTest {
        h.permissions.camera = CameraPermission.ASK
        h.permissions.cameraAfterRequest = CameraPermission.GRANTED
        val (vm, screen) = open()
        assertEquals(CameraPermission.ASK, screen.value.permission)
        assertEquals(0, h.permissions.cameraRequests)
        vm.onAction(CaptureAction.AllowCamera)
        runCurrent()
        assertEquals(1, h.permissions.cameraRequests)
        assertEquals(CameraPermission.GRANTED, screen.value.permission)
    }

    @Test
    fun aRefusalAndroidWillNotAskAgainForTurnsTheGateIntoOpenSettings() = runTest {
        h.permissions.camera = CameraPermission.ASK
        h.permissions.cameraAfterRequest = CameraPermission.BLOCKED
        val (vm, screen) = open()
        vm.onAction(CaptureAction.AllowCamera)
        runCurrent()
        assertEquals(CameraPermission.BLOCKED, screen.value.permission)
        vm.onAction(CaptureAction.OpenSettings)
        runCurrent()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.OpenAppSettings), screen.events)
        h.permissions.camera = CameraPermission.GRANTED
        vm.onAction(CaptureAction.Resumed)
        runCurrent()
        assertEquals(CameraPermission.GRANTED, screen.value.permission)
    }

    @Test
    fun flipSwitchesBetweenTheBackAndFrontCamerasAndTheModeSwitchChangesMode() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Flip)
        assertEquals(CameraFacing.FRONT, screen.value.facing)
        vm.onAction(CaptureAction.Flip)
        assertEquals(CameraFacing.BACK, screen.value.facing)
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        assertEquals(CaptureMode.VIDEO, screen.value.mode)
        vm.onAction(CaptureAction.SetMode(CaptureMode.PHOTO))
        assertEquals(CaptureMode.PHOTO, screen.value.mode)
    }

    @Test
    fun aQuickTapInPhotoModeTakesAPhotoWithAShutterTickAndAddsItToTheTray() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.ShutterDown)
        advance(CameraConstants.HOLD_TO_RECORD_MS - 1)
        assertEquals(0, h.camera.photos)
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
        assertEquals(1, h.camera.photos)
        assertEquals(listOf(HapticKind.SHUTTER), h.haptics)
        assertEquals(emptyList<Long>(), h.camera.starts)
        assertEquals(listOf(ImportCall(cameraPhoto(), ImportSlots(used = 0, limit = 20))), h.imports.calls)
        assertEquals(MediaOrigin.CAMERA, h.imports.calls.single().input.origin)
        assertEquals(listOf(mediaId("item1")), ids(screen.value))
        assertEquals(listOf(mediaId("item1")), h.tray.items.map { it.mediaId })
        assertEquals(0, screen.value.pending)
        assertNull(screen.value.recording)
    }

    @Test
    fun holdingTheShutterRecordsACappedClipInPhotoModeAndReleasingStopsIt() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.ShutterDown)
        advance(CameraConstants.HOLD_TO_RECORD_MS - 1)
        assertEquals(emptyList<Long>(), h.camera.starts)
        assertNull(screen.value.recording)
        advance(1)
        assertEquals(listOf(cap), h.camera.starts)
        assertEquals(CaptureRecording(elapsedMs = 0), screen.value.recording)
        assertEquals(listOf(HapticKind.RECORD_START), h.haptics)
        assertEquals(0, h.camera.photos)
        advance(2_000)
        assertEquals(CaptureRecording(elapsedMs = 2_000), screen.value.recording)
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
        assertEquals(1, h.camera.stops)
        assertEquals(listOf(HapticKind.RECORD_START, HapticKind.RECORD_STOP), h.haptics)
        h.camera.finish(cameraVideo())
        runCurrent()
        assertNull(screen.value.recording)
        assertEquals(listOf(cameraVideo()), h.imports.calls.map { it.input })
        assertEquals(1, screen.value.items.size)
    }

    @Test
    fun holdingTheShutterInVideoModeRecordsToo() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        holdStart(vm)
        assertEquals(listOf(cap), h.camera.starts)
        assertEquals(CaptureRecording(elapsedMs = 0), screen.value.recording)
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
        assertEquals(1, h.camera.stops)
    }

    @Test
    fun aHeldClipStopsByItselfAtTheMaximumHoldLength() = runTest {
        h.settings { it.copy(holdToRecordMaxSeconds = 5) }
        val (vm, _) = open()
        holdStart(vm)
        advance(4_999)
        assertEquals(0, h.camera.stops)
        advance(1)
        assertEquals(1, h.camera.stops)
        h.camera.finish(cameraVideo())
        runCurrent()
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
        assertEquals(1, h.camera.stops)
        assertEquals(1, h.imports.calls.size)
    }

    @Test
    fun theDefaultMaximumHoldLengthIs15Seconds() = runTest {
        val (vm, _) = open()
        holdStart(vm)
        advance(14_999)
        assertEquals(0, h.camera.stops)
        advance(1)
        assertEquals(1, h.camera.stops)
    }

    @Test
    fun releasingEarlyCancelsTheHoldCap() = runTest {
        h.settings { it.copy(holdToRecordMaxSeconds = 5) }
        val (vm, _) = open()
        holdStart(vm)
        advance(1_000)
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
        h.camera.finish(cameraVideo())
        advance(10_000)
        assertEquals(1, h.camera.stops)
    }

    @Test
    fun aClipThatEndsByItselfBeforeTheCapClearsTheCap() = runTest {
        h.settings { it.copy(holdToRecordMaxSeconds = 5) }
        val (vm, screen) = open()
        holdStart(vm)
        advance(1_000)
        h.camera.finish(cameraVideo())
        runCurrent()
        assertNull(screen.value.recording)
        advance(10_000)
        assertEquals(0, h.camera.stops)
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
        assertEquals(0, h.camera.stops)
        assertEquals(listOf(HapticKind.RECORD_START), h.haptics)
    }

    @Test
    fun inVideoModeATapStartsARecordingWithoutALengthLimitAndASecondTapStopsIt() = runTest {
        val (vm, screen) = open(entryId = null)
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        assertEquals(listOf(cap), h.camera.starts)
        assertEquals(listOf(HapticKind.RECORD_START), h.haptics)
        assertTrue(screen.value.recording != null)
        advance(200_000)
        assertEquals(0, h.camera.stops)
        tap(vm)
        assertEquals(1, h.camera.stops)
        assertEquals(listOf(HapticKind.RECORD_START, HapticKind.RECORD_STOP), h.haptics)
        assertEquals(1, h.camera.starts.size)
    }

    @Test
    fun aLaterHoldNeitherStopsNorRestartsATapRecording() = runTest {
        val (vm, _) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        holdStart(vm)
        advance(1_000)
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
        assertEquals(0, h.camera.stops)
        assertEquals(1, h.camera.starts.size)
    }

    @Test
    fun withHoldToRecordOffAHoldActsLikeATap() = runTest {
        h.settings { it.copy(holdToRecordOn = false) }
        val (vm, screen) = open()
        holdStart(vm)
        assertEquals(1, h.camera.photos)
        advance(1_000)
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
        assertEquals(1, h.camera.photos)
        assertEquals(emptyList<Long>(), h.camera.starts)
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        holdStart(vm)
        assertEquals(listOf(cap), h.camera.starts)
        vm.onAction(CaptureAction.ShutterUp)
        runCurrent()
        assertEquals(0, h.camera.stops)
        assertTrue(screen.value.recording != null)
        tap(vm)
        assertEquals(1, h.camera.stops)
    }

    @Test
    fun theRecordingTimerTicksEvery250Milliseconds() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        val start = screen.value.recording
        assertEquals(CaptureRecording(elapsedMs = 0), start)
        advance(1_100)
        assertEquals(CaptureRecording(elapsedMs = 1_000), screen.value.recording)
        advance(59_900)
        assertEquals(CaptureRecording(elapsedMs = 61_000), screen.value.recording)
        assertEquals("01:01", formatDuration(screen.value.recording!!.elapsedMs))
    }

    @Test
    fun whileRecordingTheModeSwitchFlipAndGalleryAreIgnored() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        vm.onAction(CaptureAction.SetMode(CaptureMode.PHOTO))
        vm.onAction(CaptureAction.Flip)
        vm.onAction(CaptureAction.PickFromGallery)
        runCurrent()
        assertEquals(CaptureMode.VIDEO, screen.value.mode)
        assertEquals(CameraFacing.BACK, screen.value.facing)
        assertEquals(emptyList<CaptureEvent>(), screen.events)
    }

    @Test
    fun aRecordingThatFailsLeavesNothingBehind() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        h.camera.finish(null)
        runCurrent()
        assertNull(screen.value.recording)
        assertEquals(emptyList<ImportCall>(), h.imports.calls)
        assertEquals(0, screen.value.pending)
        advance(1_000)
        assertNull(screen.value.recording)
    }

    @Test
    fun aPhotoThatFailsAddsNothing() = runTest {
        h.camera.photo = null
        val (vm, screen) = open()
        tap(vm)
        assertEquals(1, h.camera.photos)
        assertEquals(emptyList<ImportCall>(), h.imports.calls)
        assertEquals(0, screen.value.pending)
    }

    @Test
    fun theFirstVideoAsksForTheMicrophone() = runTest {
        h.permissions.microphone = false
        h.permissions.microphoneAfterRequest = true
        val (vm, screen) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        assertEquals(1, h.permissions.microphoneRequests)
        assertEquals(listOf(cap), h.camera.starts)
        assertTrue(screen.value.recording != null)
        tap(vm)
        h.camera.finish(cameraVideo())
        runCurrent()
        tap(vm)
        assertEquals(1, h.permissions.microphoneRequests)
        assertEquals(2, h.camera.starts.size)
    }

    @Test
    fun noRecordingStartsWhenTheMicrophoneIsRefused() = runTest {
        h.permissions.microphone = false
        h.permissions.microphoneAfterRequest = false
        val (vm, screen) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        assertEquals(1, h.permissions.microphoneRequests)
        assertEquals(emptyList<Long>(), h.camera.starts)
        assertNull(screen.value.recording)
    }

    @Test
    fun theRecordingSizeCapFollowsTheServerUploadLimit() = runTest {
        h.config.config = publicConfig(maxUploadBytes = 50_000_000)
        val (vm, _) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        assertEquals(listOf(maxPlaintextBytes(50_000_000)), h.camera.starts)
    }

    @Test
    fun withoutAServerConfigTheFallbackLimitsApply() = runTest {
        h.config.config = null
        val (vm, _) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        assertEquals(listOf(maxPlaintextBytes(MAX_UPLOAD_BYTES)), h.camera.starts)
        tap(vm)
        h.camera.finish(cameraVideo())
        runCurrent()
        assertEquals(ImportSlots(used = 0, limit = 20), h.imports.calls.single().slots)
    }

    @Test
    fun pickedFilesAreCopiedAndImportedOneAtATimeAgainstTheTrayCount() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.imports.gate = gate
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"), uri("b.jpg"), uri("c.mp4"))))
        runCurrent()
        assertEquals(listOf(uri("a.jpg"), uri("b.jpg"), uri("c.mp4")), h.captureFiles.copied)
        assertEquals(3, screen.value.pending)
        assertEquals(1, h.imports.calls.size)
        assertFalse(screen.value.canAddToEntry)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, screen.value.pending)
        assertEquals(
            listOf(
                ImportCall(picked("a.jpg"), ImportSlots(used = 0, limit = 20)),
                ImportCall(picked("b.jpg"), ImportSlots(used = 1, limit = 20)),
                ImportCall(picked("c.mp4"), ImportSlots(used = 2, limit = 20)),
            ),
            h.imports.calls,
        )
        assertEquals(listOf(mediaId("item1"), mediaId("item2"), mediaId("item3")), ids(screen.value))
        assertEquals(emptyList<Long>(), h.location.requests)
    }

    @Test
    fun aPickerThatReturnsNothingChangesNothing() = runTest {
        h.imports.refusals["notes.pdf"] = ImportRefusal.UNSUPPORTED
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("notes.pdf"))))
        runCurrent()
        val before = screen.value
        vm.onAction(CaptureAction.Picked(emptyList()))
        runCurrent()
        assertEquals(before, screen.value)
        assertEquals(listOf("notes.pdf isn't a supported photo or video."), texts(screen.value))
    }

    @Test
    fun aPickedFileThatCannotBeCopiedIsSkipped() = runTest {
        h.captureFiles.unreadable += "gone.jpg"
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("gone.jpg"), uri("b.jpg"))))
        runCurrent()
        assertEquals(listOf(picked("b.jpg")), h.imports.calls.map { it.input })
        assertEquals(1, screen.value.items.size)
        assertEquals(0, screen.value.pending)
    }

    @Test
    fun refusalsAreNamedOnceInOneBannerAndTheNextBatchReplacesThem() = runTest {
        h.config.config = publicConfig(maxMedia = 1)
        h.imports.refusals["notes.pdf"] = ImportRefusal.UNSUPPORTED
        h.imports.refusals["IMG_0001.heic"] = ImportRefusal.UNDECODABLE
        h.imports.refusals["huge.mp4"] = ImportRefusal.TOO_LARGE
        h.imports.refusals["broken.mp4"] = ImportRefusal.READ_FAILED
        val (vm, screen) = open()
        val batch = listOf("notes.pdf", "IMG_0001.heic", "huge.mp4", "broken.mp4", "a.jpg", "b.jpg", "c.jpg")
        vm.onAction(CaptureAction.Picked(batch.map(::uri)))
        runCurrent()
        assertEquals(
            listOf(
                "notes.pdf isn't a supported photo or video.",
                "IMG_0001.heic couldn't be opened.",
                "huge.mp4 is larger than this server allows.",
                "broken.mp4 couldn't be added. Try again.",
                "An entry can hold up to 1 photos and videos.",
            ),
            texts(screen.value),
        )
        assertEquals(listOf(mediaId("item1")), ids(screen.value))
        h.camera.photo = cameraPhoto("again.jpg")
        tap(vm)
        assertEquals(listOf("An entry can hold up to 1 photos and videos."), texts(screen.value))
        vm.onAction(CaptureAction.Remove(mediaId("item1")))
        runCurrent()
        h.imports.refusals["again.jpg"] = ImportRefusal.READ_FAILED
        tap(vm)
        assertEquals(listOf("This photo couldn't be added. Try again."), texts(screen.value))
    }

    @Test
    fun theNextBatchClearsTheBannerEvenWhenNothingInItIsRefused() = runTest {
        h.imports.refusals["notes.pdf"] = ImportRefusal.UNSUPPORTED
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("notes.pdf"))))
        runCurrent()
        assertEquals(listOf("notes.pdf isn't a supported photo or video."), texts(screen.value))
        tap(vm)
        assertEquals(emptyList<String>(), texts(screen.value))
        assertEquals(1, screen.value.items.size)
    }

    @Test
    fun aRefusedCameraVideoIsCalledThisVideo() = runTest {
        h.imports.refusals["camera-video.mp4"] = ImportRefusal.UNDECODABLE
        val (vm, screen) = open()
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        tap(vm)
        h.camera.finish(cameraVideo())
        runCurrent()
        assertEquals(listOf("This video couldn't be opened."), texts(screen.value))
        assertEquals(emptyList<CaptureTrayItem>(), screen.value.items)
    }

    @Test
    fun removingATrayItemDeletesItsFiles() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"), uri("b.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.Remove(mediaId("item1")))
        runCurrent()
        assertEquals(listOf(mediaId("item2")), ids(screen.value))
        assertEquals(listOf(mediaId("item2")), h.tray.items.map { it.mediaId })
        assertTrue(h.files.deleted.containsAll(listOf(mediaId("item1") + "-original", mediaId("item1") + "-thumbnail")))
        assertFalse(h.files.deleted.any { it.contains(mediaId("item2")) })
    }

    @Test
    fun anImportThatFinishesAfterTheTrayWasClearedDeletesItsOwnFiles() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.imports.gate = gate
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"))))
        runCurrent()
        h.tray.clear(true)
        gate.complete(Unit)
        runCurrent()
        assertEquals(emptyList<CaptureTrayItem>(), screen.value.items)
        assertEquals(0, screen.value.pending)
        assertTrue(h.files.deleted.contains(mediaId("item1") + "-original"))
    }

    @Test
    fun trayChangesMadeElsewhereShowUp() = runTest {
        val (_, screen) = open()
        h.tray.add(imported(mediaId("outside")))
        runCurrent()
        assertEquals(listOf(mediaId("outside")), ids(screen.value))
        h.tray.clear(true)
        runCurrent()
        assertEquals(emptyList<CaptureTrayItem>(), screen.value.items)
    }

    @Test
    fun theDevicePositionIsAskedOnceForTheFirstCameraCaptureWhenTheSettingIsOn() = runTest {
        h.settings { it.copy(locationForCaptures = true) }
        val (vm, _) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"))))
        runCurrent()
        assertEquals(emptyList<Long>(), h.location.requests)
        tap(vm)
        assertEquals(listOf(CaptureConstants.LOCATION_TIMEOUT_MS), h.location.requests)
        assertEquals(PARIS, h.tray.deviceLocation)
        tap(vm)
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        tap(vm)
        h.camera.finish(cameraVideo())
        runCurrent()
        assertEquals(listOf(CaptureConstants.LOCATION_TIMEOUT_MS), h.location.requests)
    }

    @Test
    fun noPositionIsAskedWhenTheSettingIsOffAndAFailedLookupIsIgnored() = runTest {
        val (vm, _) = open()
        tap(vm)
        assertEquals(emptyList<Long>(), h.location.requests)
        h.settings { it.copy(locationForCaptures = true) }
        h.location.failure = IllegalStateException("no fix")
        val (other, screen) = open()
        tap(other)
        assertEquals(listOf(CaptureConstants.LOCATION_TIMEOUT_MS), h.location.requests)
        assertNull(h.tray.deviceLocation)
        assertEquals(2, screen.value.items.size)
    }

    @Test
    fun galleryOpensThePickerWithTheSlotsLeft() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"), uri("b.jpg"))))
        runCurrent()
        h.imports.gate = gate
        vm.onAction(CaptureAction.Picked(listOf(uri("c.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.PickFromGallery)
        runCurrent()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.PickFromGallery(remainingSlots = 17)), screen.events)
    }

    @Test
    fun aFullTrayStillOpensThePickerWithNoSlotsLeft() = runTest {
        h.config.config = publicConfig(maxMedia = 1)
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.PickFromGallery)
        runCurrent()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.PickFromGallery(remainingSlots = 0)), screen.events)
    }

    @Test
    fun thePhoneCameraAppWritesToAFreshFileAndItsResultJoinsTheTray() = runTest {
        h.settings { it.copy(cameraApp = CameraApp.SYSTEM, locationForCaptures = true) }
        val (vm, screen) = open()
        vm.onAction(CaptureAction.PhoneCamera(video = false))
        runCurrent()
        val photo = h.captureFiles.outputs.single()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.OpenPhoneCamera(video = false, output = photo, authority = AUTHORITY)), screen.events)
        vm.onAction(CaptureAction.PhoneCameraResult(ok = true))
        runCurrent()
        assertEquals(listOf(ImportCall(picked(null, photo.name).copy(file = photo), ImportSlots(used = 0, limit = 20))), h.imports.calls)
        assertEquals(1, screen.value.items.size)
        assertEquals(emptyList<Long>(), h.location.requests)
        vm.onAction(CaptureAction.PhoneCamera(video = true))
        runCurrent()
        val video = h.captureFiles.outputs.last()
        assertTrue(video.name.endsWith(".mp4"))
        assertEquals(CaptureEvent.OpenPhoneCamera(video = true, output = video, authority = AUTHORITY), screen.events.last())
    }

    @Test
    fun aCancelledPhoneCaptureDeletesItsFileAndAddsNothing() = runTest {
        h.settings { it.copy(cameraApp = CameraApp.SYSTEM) }
        val (vm, screen) = open()
        vm.onAction(CaptureAction.PhoneCamera(video = false))
        runCurrent()
        val output = h.captureFiles.outputs.single()
        vm.onAction(CaptureAction.PhoneCameraResult(ok = false))
        runCurrent()
        assertFalse(output.exists())
        assertEquals(emptyList<ImportCall>(), h.imports.calls)
        assertEquals(emptyList<CaptureTrayItem>(), screen.value.items)
    }

    @Test
    fun thePhoneCameraIsNotOpenedWhenTheEntryIsFull() = runTest {
        h.settings { it.copy(cameraApp = CameraApp.SYSTEM) }
        h.config.config = publicConfig(maxMedia = 1)
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.PhoneCamera(video = false))
        runCurrent()
        assertEquals(emptyList<CaptureEvent>(), screen.events)
        assertEquals(emptyList<java.io.File>(), h.captureFiles.outputs)
    }

    @Test
    fun nextIsIgnoredWhileEmptyOrRecordingAndOtherwiseOpensTheNewEntry() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Next)
        assertEquals(emptyList<CaptureEvent>(), screen.events)
        tap(vm)
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        vm.onAction(CaptureAction.Next)
        runCurrent()
        assertEquals(emptyList<CaptureEvent>(), screen.events)
        tap(vm)
        h.camera.finish(cameraVideo())
        runCurrent()
        vm.onAction(CaptureAction.Next)
        runCurrent()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Open(Route.EntryNew)), screen.events)
        assertEquals(2, h.tray.items.size)
    }

    @Test
    fun nextDoesNotWaitForPendingImports() = runTest {
        val (vm, screen) = open()
        tap(vm)
        h.imports.gate = CompletableDeferred()
        vm.onAction(CaptureAction.Picked(listOf(uri("b.jpg"))))
        runCurrent()
        assertEquals(1, screen.value.pending)
        vm.onAction(CaptureAction.Next)
        runCurrent()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Open(Route.EntryNew)), screen.events)
    }

    @Test
    fun addingToAnEntryLimitsTheTrayToTheRoomLeftInTheEntry() = runTest {
        h.entry(mediaCount = 18)
        val (vm, screen) = open(entryId = ENTRY)
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"), uri("b.jpg"), uri("c.jpg"))))
        runCurrent()
        assertEquals(listOf(ImportSlots(0, 2), ImportSlots(1, 2), ImportSlots(2, 2)), h.imports.calls.map { it.slots })
        assertEquals(2, screen.value.items.size)
        assertEquals(listOf("An entry can hold up to 2 photos and videos."), texts(screen.value))
        vm.onAction(CaptureAction.PickFromGallery)
        runCurrent()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.PickFromGallery(remainingSlots = 0)), screen.events)
    }

    @Test
    fun addToEntryWaitsForImportsThenAddsTheItemsKeepsTheirFilesAndLeaves() = runTest {
        h.entry(mediaCount = 1)
        val (vm, screen) = open(entryId = ENTRY)
        tap(vm)
        val gate = CompletableDeferred<Unit>()
        h.imports.gate = gate
        vm.onAction(CaptureAction.Picked(listOf(uri("b.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.AddToEntry)
        runCurrent()
        assertEquals(emptyList<Pair<String, List<String>>>(), h.entries.added)
        gate.complete(Unit)
        runCurrent()
        val kept = h.tray.items.toList()
        val addGate = CompletableDeferred<Unit>()
        h.entries.addGate = addGate
        vm.onAction(CaptureAction.AddToEntry)
        runCurrent()
        assertTrue(screen.value.adding)
        addGate.complete(Unit)
        runCurrent()
        assertEquals(listOf(ENTRY to listOf(mediaId("item1"), mediaId("item2"))), h.entries.added)
        assertEquals(emptyList<CaptureTrayItem>(), screen.value.items)
        assertEquals(0, h.tray.items.size)
        assertEquals(emptyList<String>(), h.files.deleted.toList())
        assertTrue(kept.all { item -> item.mediaKey.any { it != 0.toByte() } })
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Back), screen.events)
    }

    @Test
    fun aFailedAddKeepsTheTrayAndLetsTheUserTryAgain() = runTest {
        h.entry(mediaCount = 1)
        h.entries.addFailure = IllegalStateException("disk full")
        val (vm, screen) = open(entryId = ENTRY)
        tap(vm)
        vm.onAction(CaptureAction.AddToEntry)
        runCurrent()
        assertFalse(screen.value.adding)
        assertEquals(1, screen.value.items.size)
        assertEquals(emptyList<CaptureEvent>(), screen.events)
        h.entries.addFailure = null
        vm.onAction(CaptureAction.AddToEntry)
        runCurrent()
        assertEquals(listOf(ENTRY to listOf(mediaId("item1"))), h.entries.added)
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Back), screen.events)
    }

    @Test
    fun addToEntryIsIgnoredWhileRecording() = runTest {
        h.entry(mediaCount = 1)
        val (vm, _) = open(entryId = ENTRY)
        tap(vm)
        vm.onAction(CaptureAction.SetMode(CaptureMode.VIDEO))
        tap(vm)
        vm.onAction(CaptureAction.AddToEntry)
        runCurrent()
        assertEquals(emptyList<Pair<String, List<String>>>(), h.entries.added)
    }

    @Test
    fun closeLeavesAtOnceWhenNothingIsInTheTray() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Close)
        runCurrent()
        assertNull(screen.value.dialog)
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Back), screen.events)
    }

    @Test
    fun closeAsksToDiscardTheTrayAndKeepCancels() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"), uri("b.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.Close)
        assertEquals(CaptureDialog.Discard(count = 2), screen.value.dialog)
        vm.onAction(CaptureAction.Keep)
        assertNull(screen.value.dialog)
        assertEquals(2, h.tray.items.size)
        assertEquals(emptyList<CaptureEvent>(), screen.events)
    }

    @Test
    fun discardEmptiesTheTrayDeletesItsFilesAndLeaves() = runTest {
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"), uri("b.jpg"))))
        runCurrent()
        val items = h.tray.items.toList()
        val generation = h.tray.generation
        vm.onAction(CaptureAction.Close)
        vm.onAction(CaptureAction.Discard)
        runCurrent()
        assertEquals(0, h.tray.items.size)
        assertEquals(generation + 1, h.tray.generation)
        assertTrue(items.all { item -> item.mediaKey.all { it == 0.toByte() } })
        assertTrue(h.files.deleted.containsAll(items.map { it.originalObjectId }))
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Back), screen.events)
    }

    @Test
    fun closeAlsoAsksWhileImportsArePending() = runTest {
        h.imports.gate = CompletableDeferred()
        val (vm, screen) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.Close)
        assertEquals(CaptureDialog.Discard(count = 0), screen.value.dialog)
    }

    @Test
    fun addingToAnEntryWithItemsLeftInTheTrayAsksToDiscardThemFirst() = runTest {
        h.entry(mediaCount = 1)
        h.tray.add(imported(mediaId("left1")))
        h.tray.add(imported(mediaId("left2")))
        val (vm, screen) = open(entryId = ENTRY)
        assertEquals(CaptureDialog.Leftover(count = 2), screen.value.dialog)
        vm.onAction(CaptureAction.Discard)
        runCurrent()
        assertNull(screen.value.dialog)
        assertEquals(0, h.tray.items.size)
        assertTrue(h.files.deleted.contains(mediaId("left1") + "-original"))
        assertEquals(emptyList<CaptureEvent>(), screen.events)
    }

    @Test
    fun cancelOnTheLeftoverQuestionLeavesTheTrayAlone() = runTest {
        h.entry(mediaCount = 1)
        h.tray.add(imported(mediaId("left1")))
        val (vm, screen) = open(entryId = ENTRY)
        vm.onAction(CaptureAction.CancelAdd)
        runCurrent()
        assertEquals(listOf<CaptureEvent>(CaptureEvent.Back), screen.events)
        assertEquals(1, h.tray.items.size)
        assertEquals(emptyList<String>(), h.files.deleted.toList())
    }

    @Test
    fun aNewEntryKeepsWhatIsAlreadyInTheTray() = runTest {
        h.tray.add(imported(mediaId("left1")))
        val (_, screen) = open()
        assertNull(screen.value.dialog)
        assertEquals(listOf(mediaId("left1")), ids(screen.value))
        assertTrue(screen.value.canNext)
    }

    private companion object {
        const val TAP_MS = 80L
    }

    @Test
    fun f15EveryPickedImportIsReportedWithItsMediaIdOrNullWhenRefused() = runTest {
        h.imports.refusals["notes.pdf"] = ImportRefusal.UNSUPPORTED
        val (vm, _) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"), uri("notes.pdf"), uri("b.jpg"))))
        runCurrent()
        assertEquals(
            listOf(picked("a.jpg") to mediaId("item1"), picked("notes.pdf") to null, picked("b.jpg") to mediaId("item2")),
            h.captureFiles.imported,
        )
    }

    @Test
    fun f15RemovingATrayItemForgetsItsDeviceOriginal() = runTest {
        val (vm, _) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"), uri("b.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.Remove(mediaId("item1")))
        runCurrent()
        assertEquals(listOf(listOf(mediaId("item1"))), h.captureFiles.forgotten)
    }

    @Test
    fun f15DiscardingTheTrayForgetsEveryDeviceOriginalInIt() = runTest {
        val (vm, _) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"), uri("b.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.Close)
        vm.onAction(CaptureAction.Discard)
        runCurrent()
        assertEquals(listOf(listOf(mediaId("item1"), mediaId("item2"))), h.captureFiles.forgotten)
    }

    @Test
    fun f15DiscardingLeftoversForgetsTheirDeviceOriginals() = runTest {
        h.entry(mediaCount = 1)
        h.tray.add(imported(mediaId("left1")))
        val (vm, _) = open(entryId = ENTRY)
        vm.onAction(CaptureAction.Discard)
        runCurrent()
        assertEquals(listOf(listOf(mediaId("left1"))), h.captureFiles.forgotten)
    }

    @Test
    fun f15KeepingTheTrayForgetsNothing() = runTest {
        val (vm, _) = open()
        vm.onAction(CaptureAction.Picked(listOf(uri("a.jpg"))))
        runCurrent()
        vm.onAction(CaptureAction.Close)
        vm.onAction(CaptureAction.Keep)
        runCurrent()
        assertEquals(emptyList<List<String>>(), h.captureFiles.forgotten)
    }
}
