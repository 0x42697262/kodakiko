package dev.kodakiko.data.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UploadRegistryTest {
    private val registry = UploadRegistry()

    private fun job(id: String, vararg lengths: Long) =
        UploadJob(id, "/tmp/$id", lengths.mapIndexed { index, length -> UploadPart(index + 1, "u", 0, length) })

    @Test
    fun failPendingEmptiesTheQueueAndMarksEveryQueuedJobFailed() {
        registry.submit(listOf(job("a", 10), job("b", 20)))
        registry.failPending()
        assertFalse(registry.hasPending())
        assertEquals(mapOf("a" to UploadStatus.FAILED, "b" to UploadStatus.FAILED), registry.snapshot())
    }

    @Test
    fun failPendingLeavesFinishedJobsAlone() {
        registry.submit(listOf(job("a", 10), job("b", 20)))
        registry.poll()
        registry.setStatus("a", UploadStatus.COMPLETED)
        registry.failPending()
        assertEquals(mapOf("a" to UploadStatus.COMPLETED, "b" to UploadStatus.FAILED), registry.snapshot())
    }

    @Test
    fun progressSubtractsBytesOfAFailedAttempt() {
        registry.submit(listOf(job("a", 100)))
        registry.addSent(60)
        registry.addSent(-60)
        registry.addSent(30)
        assertEquals(Pair(30L, 100L), registry.progress())
    }

    @Test
    fun percentIsNeverAbove100OrBelow0AndZeroWhenNothingIsQueued() {
        assertEquals(0, registry.percent())
        registry.submit(listOf(job("a", 100)))
        registry.addSent(250)
        assertEquals(100, registry.percent())
        registry.addSent(-1000)
        assertEquals(0, registry.percent())
    }

    @Test
    fun itemsLeftCountsQueuedAndUploadingJobs() {
        registry.submit(listOf(job("a", 1), job("b", 1), job("c", 1)))
        registry.poll()
        registry.setStatus("a", UploadStatus.UPLOADING)
        assertEquals(3, registry.itemsLeft())
        registry.setStatus("a", UploadStatus.COMPLETED)
        assertEquals(2, registry.itemsLeft())
    }

    @Test
    fun listenersCanUnsubscribe() {
        val seen = mutableListOf<UploadEvent>()
        val unsubscribe = registry.addListener { seen += it }
        registry.emit(UploadEvent.Completed("a"))
        unsubscribe()
        registry.emit(UploadEvent.Completed("b"))
        assertEquals(listOf<UploadEvent>(UploadEvent.Completed("a")), seen)
    }
}
