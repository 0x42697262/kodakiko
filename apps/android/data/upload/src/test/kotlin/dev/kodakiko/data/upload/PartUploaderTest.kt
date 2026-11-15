package dev.kodakiko.data.upload

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

class PartUploaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val registry = UploadRegistry()
    private val events = Collections.synchronizedList(mutableListOf<UploadEvent>())
    private val sleeps = Collections.synchronizedList(mutableListOf<Long>())
    private val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private lateinit var file: File

    private fun uploader() = PartUploader(
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(5, TimeUnit.SECONDS).build(),
        registry = registry,
        sleep = { sleeps += it },
        onProgress = {},
    )

    private fun parts(vararg lengths: Int): List<UploadPart> {
        var offset = 0L
        return lengths.mapIndexed { index, length ->
            UploadPart(index + 1, server.url("/put/${index + 1}").toString(), offset, length.toLong()).also { offset += length }
        }
    }

    private fun ok() = MockResponse.Builder().code(200).build()

    private fun disconnect() = MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()

    @Before
    fun start() {
        server.start()
        file = temp.newFile("object.bin").apply { writeBytes(bytes) }
        registry.addListener { events += it }
    }

    @After
    fun stop() = server.close()

    @Test
    fun putsEveryPartAsItsExactByteRangeAndReportsCumulativeProgress() = runBlocking {
        repeat(3) { server.enqueue(ok()) }
        registry.submit(listOf(UploadJob("obj", file.path, parts(100_000, 150_000, 50_000))))
        uploader().drain()
        val received = (1..3).map { server.takeRequest() }.sortedBy { it.url.encodedPath }
        assertEquals(listOf("/put/1", "/put/2", "/put/3"), received.map { it.url.encodedPath })
        assertTrue(received.all { it.method == "PUT" && it.headers["Content-Type"] == "application/octet-stream" })
        assertEquals(listOf("100000", "150000", "50000"), received.map { it.headers["Content-Length"] })
        assertArrayEquals(bytes, received.fold(ByteArray(0)) { acc, request -> acc + request.body!!.toByteArray() })
        assertEquals(
            listOf(UploadEvent.Progress("obj", 100_000), UploadEvent.Progress("obj", 250_000), UploadEvent.Progress("obj", 300_000), UploadEvent.Completed("obj")),
            events.toList(),
        )
        assertEquals(UploadStatus.COMPLETED, registry.snapshot()["obj"])
        assertEquals(Pair(300_000L, 300_000L), registry.progress())
    }

    @Test
    fun aFileUriPathIsResolved() = runBlocking {
        server.enqueue(ok())
        registry.submit(listOf(UploadJob("obj", file.toURI().toString(), parts(300_000))))
        uploader().drain()
        assertEquals(UploadEvent.Completed("obj"), events.last())
    }

    @Test
    fun forbiddenMeansTheUrlsExpiredAndStopsTheJobWithoutRetrying() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).build())
        registry.submit(listOf(UploadJob("obj", file.path, parts(100_000, 200_000))))
        uploader().drain()
        assertEquals(listOf<UploadEvent>(UploadEvent.Failed("obj", FailureReason.EXPIRED)), events.toList())
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), sleeps.toList())
        assertEquals(UploadStatus.FAILED, registry.snapshot()["obj"])
        assertEquals(0L, registry.progress().first)
    }

    @Test
    fun anotherHttpErrorFailsWithHttpWithoutRetrying() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        registry.submit(listOf(UploadJob("obj", file.path, parts(300_000))))
        uploader().drain()
        assertEquals(listOf<UploadEvent>(UploadEvent.Failed("obj", FailureReason.HTTP)), events.toList())
        assertEquals(emptyList<Long>(), sleeps.toList())
    }

    @Test
    fun networkFailuresAreRetriedWithTheV031DelaysThenGiveUp() = runBlocking {
        repeat(4) { server.enqueue(disconnect()) }
        registry.submit(listOf(UploadJob("obj", file.path, parts(300_000))))
        uploader().drain()
        assertEquals(listOf(2_000L, 8_000L, 30_000L), sleeps.toList())
        assertEquals(listOf<UploadEvent>(UploadEvent.Failed("obj", FailureReason.NETWORK)), events.toList())
        assertEquals(0L, registry.progress().first)
    }

    @Test
    fun aNetworkFailureFollowedBySuccessCompletesWithExactProgress() = runBlocking {
        server.enqueue(disconnect())
        server.enqueue(ok())
        registry.submit(listOf(UploadJob("obj", file.path, parts(300_000))))
        uploader().drain()
        assertEquals(listOf(2_000L), sleeps.toList())
        assertEquals(UploadEvent.Completed("obj"), events.last())
        assertEquals(Pair(300_000L, 300_000L), registry.progress())
    }

    @Test
    fun aMissingOrShortFileFailsWithMissingFile() = runBlocking {
        server.enqueue(ok())
        registry.submit(
            listOf(
                UploadJob("gone", File(temp.root, "absent.bin").path, parts(10)),
                UploadJob("short", file.path, listOf(UploadPart(1, server.url("/put/1").toString(), 299_990, 100))),
            ),
        )
        uploader().drain()
        assertEquals(
            setOf<UploadEvent>(UploadEvent.Failed("gone", FailureReason.MISSING_FILE), UploadEvent.Failed("short", FailureReason.MISSING_FILE)),
            events.toSet(),
        )
    }

    @Test
    fun drainUploadsEveryQueuedJobWithAtMostThreeInFlight() = runBlocking {
        repeat(5) { server.enqueue(ok().newBuilder().headersDelay(200, TimeUnit.MILLISECONDS).build()) }
        registry.submit((1..5).map { UploadJob("obj$it", file.path, listOf(UploadPart(1, server.url("/put/$it").toString(), 0, 1_000))) })
        val started = System.nanoTime()
        uploader().drain()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(5, events.count { it is UploadEvent.Completed })
        assertEquals(3, UploadConstants.PART_CONCURRENCY)
        assertTrue("took $elapsedMs ms", elapsedMs >= 400)
    }
}
