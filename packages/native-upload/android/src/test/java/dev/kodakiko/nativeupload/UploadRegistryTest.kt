package dev.kodakiko.nativeupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class UploadRegistryTest {
  @Before
  fun reset() {
    UploadRegistry.clear()
  }

  private fun job(id: String, vararg lengths: Long) =
    UploadJob(id, "/tmp/$id", lengths.mapIndexed { index, length -> UploadPart(index + 1, "u", 0, length) })

  @Test
  fun failPendingEmptiesTheQueueAndMarksEveryQueuedJobFailed() {
    UploadRegistry.submit(listOf(job("a", 10), job("b", 20)))

    UploadRegistry.failPending()

    assertFalse(UploadRegistry.hasPending())
    assertEquals(mapOf("a" to "failed", "b" to "failed"), UploadRegistry.snapshot())
  }

  @Test
  fun failPendingLeavesFinishedJobsAlone() {
    UploadRegistry.submit(listOf(job("a", 10), job("b", 20)))
    UploadRegistry.poll()
    UploadRegistry.setStatus("a", UploadStatus.COMPLETED)

    UploadRegistry.failPending()

    assertEquals(mapOf("a" to "completed", "b" to "failed"), UploadRegistry.snapshot())
  }

  @Test
  fun progressSubtractsBytesOfAFailedAttempt() {
    UploadRegistry.submit(listOf(job("a", 100)))
    UploadRegistry.addSent(60)
    UploadRegistry.addSent(-60)
    UploadRegistry.addSent(30)

    assertEquals(Pair(30L, 100L), UploadRegistry.progress())
  }

  @Test
  fun percentIsNeverAbove100OrBelow0() {
    UploadRegistry.submit(listOf(job("a", 100)))
    UploadRegistry.addSent(250)
    assertEquals(100, UploadRegistry.percent())

    UploadRegistry.addSent(-1000)
    assertEquals(0, UploadRegistry.percent())
  }

  @Test
  fun percentIsZeroWhenNothingIsQueued() {
    assertEquals(0, UploadRegistry.percent())
  }
}
