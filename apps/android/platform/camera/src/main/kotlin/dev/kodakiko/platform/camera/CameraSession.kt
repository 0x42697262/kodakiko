package dev.kodakiko.platform.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

class CameraSession(context: Context, private val lifecycleOwner: LifecycleOwner) {
    private val appContext = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var bindGeneration = 0

    fun bind(facing: CameraFacing, preview: Preview.SurfaceProvider?, onResult: (Boolean) -> Unit) {
        val generation = ++bindGeneration
        if (!granted(Manifest.permission.CAMERA)) {
            onResult(false)
            return
        }
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (generation != bindGeneration) {
                onResult(false)
                return@addListener
            }
            val bound = runCatching { bindUseCases(future.get(), facing, preview) }.getOrDefault(false)
            onResult(bound)
        }, mainExecutor)
    }

    fun takePhoto(output: File, onResult: (PhotoResult) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            mainExecutor.execute { onResult(PhotoResult.Failed(IllegalStateException(CameraConstants.NOT_BOUND_MESSAGE))) }
            return
        }
        val options = ImageCapture.OutputFileOptions.Builder(output).build()
        capture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onResult(PhotoResult.Saved(output))
                }

                override fun onError(exception: ImageCaptureException) {
                    output.delete()
                    onResult(PhotoResult.Failed(exception))
                }
            },
        )
    }

    fun startRecording(output: File, maxFileSizeBytes: Long, withAudio: Boolean, onFinished: (VideoResult) -> Unit) {
        val capture = videoCapture
        if (capture == null || recording != null) {
            val message = if (capture == null) CameraConstants.NOT_BOUND_MESSAGE else CameraConstants.ALREADY_RECORDING_MESSAGE
            mainExecutor.execute { onFinished(VideoResult.Failed(IllegalStateException(message))) }
            return
        }
        val capped = maxFileSizeBytes > CameraConstants.NO_FILE_SIZE_LIMIT
        if (capped && maxFileSizeBytes <= CameraConstants.MUXER_RESERVED_BYTES) {
            mainExecutor.execute { onFinished(VideoResult.Failed(IllegalArgumentException(CameraConstants.CAP_TOO_SMALL_MESSAGE))) }
            return
        }
        val options = FileOutputOptions.Builder(output).apply {
            if (capped) setFileSizeLimit(maxFileSizeBytes - CameraConstants.MUXER_RESERVED_BYTES)
        }.build()
        val pending = capture.output.prepareRecording(appContext, options)
        if (withAudio && ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        }
        var started: Recording? = null
        started = try {
            pending.start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (recording === started) recording = null
                    onFinished(videoResult(output, event))
                }
            }
        } catch (e: IllegalStateException) {
            output.delete()
            mainExecutor.execute { onFinished(VideoResult.Failed(e)) }
            return
        }
        recording = started
    }

    fun stopRecording() {
        recording?.stop()
    }

    fun isRecording(): Boolean = recording != null

    fun release() {
        bindGeneration++
        recording?.stop()
        provider?.unbindAll()
        imageCapture = null
        videoCapture = null
    }

    private fun bindUseCases(cameraProvider: ProcessCameraProvider, facing: CameraFacing, preview: Preview.SurfaceProvider?): Boolean {
        provider = cameraProvider
        recording?.stop()
        cameraProvider.unbindAll()
        imageCapture = null
        videoCapture = null
        val selector = selectorOf(facing)
        if (!cameraProvider.hasCamera(selector)) return false
        val photo = ImageCapture.Builder()
            .setJpegQuality(CameraConstants.PHOTO_JPEG_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
        val video = VideoCapture.withOutput(Recorder.Builder().build())
        val captures = listOf<UseCase>(photo, video)
        try {
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, *(captures + listOfNotNull(preview?.let(::previewOf))).toTypedArray())
        } catch (unsupported: IllegalArgumentException) {
            if (preview != null) throw unsupported
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, *(captures + Preview.Builder().build()).toTypedArray())
        }
        imageCapture = photo
        videoCapture = video
        return true
    }

    private fun videoResult(output: File, event: VideoRecordEvent.Finalize): VideoResult = when {
        !event.hasError() -> VideoResult.Saved(output, reachedSizeLimit = false)
        event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> VideoResult.Saved(output, reachedSizeLimit = true)
        event.error in KEEPS_FILE && output.length() > 0 -> VideoResult.Saved(output, reachedSizeLimit = false)
        else -> {
            output.delete()
            VideoResult.Failed(RecordingFailedException(event.error, event.cause))
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private val KEEPS_FILE = setOf(
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
        )

        private fun previewOf(surfaceProvider: Preview.SurfaceProvider): Preview =
            Preview.Builder().build().apply { setSurfaceProvider(surfaceProvider) }

        private fun selectorOf(facing: CameraFacing): CameraSelector = when (facing) {
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        fun availableFacings(context: Context): Set<CameraFacing> {
            val manager = context.getSystemService(CameraManager::class.java) ?: return emptySet()
            val lensFacings = try {
                manager.cameraIdList.mapNotNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }
            } catch (_: CameraAccessException) {
                emptyList()
            }
            return lensFacings.mapNotNull { lens ->
                when (lens) {
                    CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
                    else -> null
                }
            }.toSet()
        }
    }
}
