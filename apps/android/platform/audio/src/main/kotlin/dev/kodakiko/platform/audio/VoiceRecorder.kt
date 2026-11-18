package dev.kodakiko.platform.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class VoiceRecorder(
    context: Context,
    private val maxDurationMs: Long = AudioConstants.VOICE_MAX_MS,
    private val onMaxReached: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var session: Session? = null
    private var finished: RecordedVoice? = null

    private class Session(
        val recorder: MediaRecorder,
        val output: File,
        val startedAtMs: Long,
        val timer: ScheduledExecutorService,
    ) {
        var deadline: ScheduledFuture<*>? = null
    }

    fun start(output: File) {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw RecordAudioPermissionMissingException()
        }
        synchronized(lock) {
            check(session == null) { AudioConstants.ALREADY_RECORDING_MESSAGE }
            finished = null
            val recorder = prepareRecorder(output)
            try {
                recorder.start()
            } catch (e: RuntimeException) {
                recorder.release()
                throw e
            }
            val active = Session(recorder, output, SystemClock.elapsedRealtime(), Executors.newSingleThreadScheduledExecutor())
            session = active
            active.deadline = active.timer.schedule({ reachMax(active) }, maxDurationMs, TimeUnit.MILLISECONDS)
        }
    }

    fun isRecording(): Boolean = synchronized(lock) { session != null }

    fun elapsedMs(): Long = synchronized(lock) {
        session?.let { SystemClock.elapsedRealtime() - it.startedAtMs } ?: finished?.durationMs ?: AudioConstants.NO_DURATION_MS
    }

    fun stop(): RecordedVoice? = synchronized(lock) {
        val active = session
        if (active == null) finished.also { finished = null } else finish(active)
    }

    private fun reachMax(expected: Session) {
        synchronized(lock) {
            if (session !== expected) return
            finished = finish(expected)
        }
        onMaxReached()
    }

    private fun finish(active: Session): RecordedVoice? {
        session = null
        val durationMs = SystemClock.elapsedRealtime() - active.startedAtMs
        active.deadline?.cancel(false)
        active.timer.shutdown()
        val stopped = try {
            active.recorder.stop()
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            active.recorder.release()
        }
        if (!stopped) {
            active.output.delete()
            return null
        }
        return RecordedVoice(active.output, durationMs, AudioConstants.MIME_TYPE)
    }

    private fun prepareRecorder(output: File): MediaRecorder {
        val recorder = newMediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(AudioConstants.SAMPLE_RATE)
            recorder.setAudioChannels(AudioConstants.CHANNELS)
            recorder.setAudioEncodingBitRate(AudioConstants.BIT_RATE)
            recorder.setOutputFile(output.path)
            recorder.prepare()
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
        return recorder
    }

    @Suppress("DEPRECATION")
    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()
}
