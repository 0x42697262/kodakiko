package dev.kodakiko.nativeupload

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object UploadRegistry {
  private val statuses = ConcurrentHashMap<String, UploadStatus>()
  private val pending = ConcurrentLinkedQueue<UploadJob>()
  private val listeners = CopyOnWriteArrayList<(UploadEvent) -> Unit>()
  private val queuedBytes = AtomicLong(0)
  private val sentBytes = AtomicLong(0)

  fun submit(jobs: List<UploadJob>) {
    for (job in jobs) {
      statuses[job.objectId] = UploadStatus.QUEUED
      pending.add(job)
      queuedBytes.addAndGet(job.totalBytes)
    }
  }

  fun poll(): UploadJob? = pending.poll()

  fun hasPending(): Boolean = pending.isNotEmpty()

  fun failPending() {
    while (true) {
      val job = pending.poll() ?: break
      statuses[job.objectId] = UploadStatus.FAILED
    }
  }

  fun percent(): Int {
    val (sent, total) = progress()
    if (total <= 0) return 0
    return ((sent * UploadConstants.PERCENT_MAX) / total).coerceIn(0L, UploadConstants.PERCENT_MAX.toLong()).toInt()
  }

  fun itemsLeft(): Int = pending.size + statuses.values.count { it == UploadStatus.UPLOADING }

  fun setStatus(objectId: String, status: UploadStatus) {
    statuses[objectId] = status
  }

  fun snapshot(): Map<String, String> = statuses.mapValues { it.value.wire }

  fun addSent(bytes: Long): Long = sentBytes.addAndGet(bytes)

  fun progress(): Pair<Long, Long> = Pair(sentBytes.get(), queuedBytes.get())

  fun resetProgress() {
    queuedBytes.set(0)
    sentBytes.set(0)
  }

  fun clear() {
    pending.clear()
    statuses.clear()
    resetProgress()
  }

  fun addListener(listener: (UploadEvent) -> Unit): () -> Unit {
    listeners.add(listener)
    return { listeners.remove(listener) }
  }

  fun emit(event: UploadEvent) {
    for (listener in listeners) listener(event)
  }
}
