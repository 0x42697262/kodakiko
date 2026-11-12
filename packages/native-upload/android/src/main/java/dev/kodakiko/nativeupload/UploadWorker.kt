package dev.kodakiko.nativeupload

import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private sealed class PartOutcome {
  object Sent : PartOutcome()
  data class Rejected(val reason: FailureReason) : PartOutcome()
}

class UploadWorker(
  private val client: OkHttpClient,
  private val onProgress: () -> Unit,
) {
  suspend fun drain() = coroutineScope {
    repeat(UploadConstants.PART_CONCURRENCY) {
      launch(Dispatchers.IO) {
        while (isActive) {
          val job = UploadRegistry.poll() ?: break
          upload(job)
        }
      }
    }
  }

  private fun resolveFile(path: String): File =
    File(
      if (path.startsWith(UploadConstants.FILE_URI_PREFIX)) {
        java.net.URI(path).path
      } else {
        path
      },
    )

  private suspend fun upload(job: UploadJob) {
    val file = resolveFile(job.filePath)
    if (!file.isFile) {
      fail(job.objectId, FailureReason.MISSING_FILE)
      return
    }
    UploadRegistry.setStatus(job.objectId, UploadStatus.UPLOADING)
    var sent = 0L
    try {
      for (part in job.parts) {
        when (val outcome = putPart(file, part)) {
          is PartOutcome.Sent -> {
            sent += part.length
            UploadRegistry.emit(UploadEvent.Progress(job.objectId, sent))
          }
          is PartOutcome.Rejected -> {
            fail(job.objectId, outcome.reason)
            return
          }
        }
      }
    } catch (cancelled: CancellationException) {
      UploadRegistry.setStatus(job.objectId, UploadStatus.FAILED)
      throw cancelled
    }
    UploadRegistry.setStatus(job.objectId, UploadStatus.COMPLETED)
    UploadRegistry.emit(UploadEvent.Completed(job.objectId))
  }

  private fun fail(objectId: String, reason: FailureReason) {
    UploadRegistry.setStatus(objectId, UploadStatus.FAILED)
    UploadRegistry.emit(UploadEvent.Failed(objectId, reason))
  }

  private suspend fun putPart(file: File, part: UploadPart): PartOutcome {
    var attempt = 0
    while (true) {
      try {
        return send(file, part)
      } catch (missing: MissingBytesException) {
        return PartOutcome.Rejected(FailureReason.MISSING_FILE)
      } catch (failure: IOException) {
        val wait = UploadConstants.RETRY_DELAYS_MS.getOrNull(attempt)
          ?: return PartOutcome.Rejected(FailureReason.NETWORK)
        attempt += 1
        delay(wait)
      }
    }
  }

  private suspend fun send(file: File, part: UploadPart): PartOutcome {
    val body = FileRangeBody(file, part.offset, part.length) { bytes ->
      UploadRegistry.addSent(bytes)
      onProgress()
    }
    val request = Request.Builder().url(part.url).put(body).build()
    val response = try {
      execute(client.newCall(request))
    } catch (failure: Throwable) {
      UploadRegistry.addSent(-body.sentBytes)
      throw failure
    }
    response.use {
      if (it.isSuccessful) return PartOutcome.Sent
      UploadRegistry.addSent(-body.sentBytes)
      return PartOutcome.Rejected(
        if (it.code == UploadConstants.HTTP_FORBIDDEN) FailureReason.EXPIRED else FailureReason.HTTP,
      )
    }
  }

  private suspend fun execute(call: Call): Response =
    suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
          }
        },
      )
    }
}
