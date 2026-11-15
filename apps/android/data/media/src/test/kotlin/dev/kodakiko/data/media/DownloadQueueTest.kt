package dev.kodakiko.data.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryPurpose
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.HttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class DownloadQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hash = "00112233445566bb"
    private val server = MockWebServer()
    private val pool = Executors.newCachedThreadPool()
    private lateinit var database: KodakikoDatabase
    private lateinit var files: FileStore
    private val bucket = ConcurrentHashMap<String, ByteArray>()
    private val apiFailures = ConcurrentHashMap<String, MutableList<Int>>()
    private val bucketFailures = ConcurrentHashMap<String, MutableList<String>>()
    private val urlRequests = ConcurrentHashMap<String, AtomicInteger>()
    private val bucketOrder = Collections.synchronizedList(mutableListOf<String>())
    private val inFlight = ConcurrentHashMap<ObjectVariant, AtomicInteger>()
    private val maxInFlight = ConcurrentHashMap<ObjectVariant, AtomicInteger>()
    private val sleeps = Collections.synchronizedList(mutableListOf<Long>())
    private var bucketDelayMs = 0L
    private val bucketDelays = ConcurrentHashMap<String, Long>()
    private var offline = false

    @Volatile private var gate: CountDownLatch? = null
    private var clock = 5_000L

    private fun id(prefix: String, n: Int) = (prefix + n).padEnd(22, 'x')

    private fun variantOf(objectId: String) = if (objectId.startsWith("t")) ObjectVariant.THUMBNAIL else ObjectVariant.ORIGINAL

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            Regex("/api/v1/objects/([^/]+)/download").matchEntire(path)?.let { match ->
                val objectId = match.groupValues[1]
                val n = urlRequests.getOrPut(objectId) { AtomicInteger() }.incrementAndGet()
                apiFailures[objectId]?.let { list -> synchronized(list) { list.removeFirstOrNull() } }?.let { status ->
                    return MockResponse.Builder().code(status).addHeader("content-type", "application/json")
                        .body("{\"error\":{\"code\":\"${if (status == 404) "NOT_FOUND" else "STORAGE_UNAVAILABLE"}\",\"message\":\"m\",\"requestId\":\"r\",\"details\":null}}").build()
                }
                return MockResponse.Builder().code(200).addHeader("content-type", "application/json")
                    .body("{\"url\":\"${server.url("/bucket/$objectId?n=$n")}\",\"expiresAt\":\"2030-01-01T00:00:00.000Z\"}").build()
            }
            val objectId = path.removePrefix("/bucket/")
            val variant = variantOf(objectId)
            val now = inFlight.getOrPut(variant) { AtomicInteger() }.incrementAndGet()
            maxInFlight.getOrPut(variant) { AtomicInteger() }.accumulateAndGet(now, ::maxOf)
            try {
                bucketOrder += objectId
                gate?.await(10, TimeUnit.SECONDS)
                if (bucketDelayMs > 0) Thread.sleep(bucketDelayMs)
                bucketDelays[objectId]?.let { Thread.sleep(it) }
                val failure = bucketFailures[objectId]?.let { list -> synchronized(list) { list.removeFirstOrNull() } }
                if (failure == "serverError") return MockResponse.Builder().code(503).build()
                val bytes = bucket[objectId] ?: return MockResponse.Builder().code(404).build()
                val body = if (failure == "wrongSize") bytes.copyOfRange(1, bytes.size) else bytes
                return MockResponse.Builder().code(200).body(okio.Buffer().write(body)).build()
            } finally {
                inFlight.getValue(variant).decrementAndGet()
            }
        }
    }

    private fun queue() = DownloadQueue(
        database = database,
        files = files,
        http = HttpClient(baseUrl = { server.url("").toString().trimEnd('/') }, sessionToken = { "kdk_s_x" }, onSessionInvalid = {}),
        sleep = { sleeps += it },
        now = { clock },
        isOffline = { offline },
    )

    private fun addObject(objectId: String, size: Int, local: ObjectLocalState = ObjectLocalState.MISSING): ByteArray {
        val bytes = ByteArray(size) { (it * 13 + objectId.hashCode()).toByte() }
        val mediaId = "m" + objectId.drop(1)
        database.transaction { tx ->
            tx.entries.put(EntryRow("e" + objectId.drop(1), "owner", EntryPurpose.JOURNAL, 1, ByteArray(1), null, null, null, null, null, "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z", EntryLocalState.SYNCED))
            tx.media.put(MediaRow(mediaId, "e" + objectId.drop(1), ByteArray(1)))
            tx.objects.put(ObjectRow(objectId, mediaId, variantOf(objectId), size.toLong(), size.toLong() - 40, local, ObjectUploadState.UPLOADED, null, 0))
        }
        bucket[objectId] = bytes
        return bytes
    }

    private fun row(objectId: String) = database.transaction { it.objects.get(objectId)!! }

    private fun async(block: () -> Unit): Future<*> = pool.submit(block)

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not reached" }
            Thread.sleep(5)
        }
    }

    @Before
    fun start() {
        server.dispatcher = dispatcher
        server.start()
        database = KodakikoDatabase.open(context, hash)
        files = FileStore.open(context, hash)
    }

    @After
    fun stop() {
        gate?.countDown()
        pool.shutdownNow()
        server.close()
        database.close()
        files.destroy()
        KodakikoDatabase.databaseFile(context, hash).parentFile!!.listFiles()!!.forEach { it.delete() }
    }

    @Test
    fun downloadsAnObjectOnceEvenWhenThreeCallersAskAtTheSameTime() {
        val objectId = id("t", 1)
        val bytes = addObject(objectId, 2_000)
        bucketDelayMs = 200
        val queue = queue()
        val completed = Collections.synchronizedList(mutableListOf<String>())
        queue.onCompleted { completed += it }
        (1..3).map { async { queue.fetch(objectId, DownloadPriority.VISIBLE) } }.forEach { it.get(10, TimeUnit.SECONDS) }
        assertEquals(1, urlRequests.getValue(objectId).get())
        assertEquals(ObjectLocalState.PRESENT, row(objectId).localState)
        assertEquals(clock, row(objectId).lastAccessAt)
        assertArrayEquals(bytes, files.readRange(objectId, 0, 2_000))
        assertEquals(listOf(objectId), completed.toList())
        queue.fetch(objectId, DownloadPriority.VISIBLE)
        assertEquals(1, urlRequests.getValue(objectId).get())
    }

    @Test
    fun anApiNotFoundMarksTheObjectUnavailableWithoutRetrying() {
        val objectId = id("t", 1)
        addObject(objectId, 2_000)
        apiFailures[objectId] = mutableListOf(404)
        val queue = queue()
        assertThrows(ObjectUnavailableError::class.java) { queue.fetch(objectId, DownloadPriority.VISIBLE) }
        assertEquals(1, urlRequests.getValue(objectId).get())
        assertEquals(ObjectLocalState.UNAVAILABLE, row(objectId).localState)
        assertThrows(ObjectUnavailableError::class.java) { queue.fetch(objectId, DownloadPriority.VISIBLE) }
        assertThrows(ObjectUnavailableError::class.java) { queue.fetch(id("o", 99), DownloadPriority.VISIBLE) }
        assertEquals(1, urlRequests.getValue(objectId).get())
    }

    @Test
    fun bucketServerErrorsRetryWithFreshUrlsAndTheV031Delays() {
        val objectId = id("t", 1)
        addObject(objectId, 2_000)
        bucketFailures[objectId] = mutableListOf("serverError", "serverError")
        queue().fetch(objectId, DownloadPriority.VISIBLE)
        assertEquals(3, urlRequests.getValue(objectId).get())
        assertEquals(listOf(2_000L, 8_000L), sleeps.toList())
        assertEquals(ObjectLocalState.PRESENT, row(objectId).localState)
    }

    @Test
    fun apiServerErrorsAreRetriedThenReportedAndOtherApiErrorsAreNot() {
        val retried = id("t", 1)
        val refused = id("t", 2)
        addObject(retried, 100)
        addObject(refused, 100)
        apiFailures[retried] = mutableListOf(503, 503, 503, 503, 503, 503, 503, 503, 503, 503, 503, 503)
        apiFailures[refused] = mutableListOf(403)
        val queue = queue()
        val error = assertThrows(ApiError::class.java) { queue.fetch(retried, DownloadPriority.VISIBLE) }
        assertEquals(ErrorCode.STORAGE_UNAVAILABLE, error.code)
        assertTrue(sleeps.containsAll(listOf(2_000L, 8_000L, 30_000L)))
        sleeps.clear()
        assertThrows(ApiError::class.java) { queue.fetch(refused, DownloadPriority.VISIBLE) }
        assertEquals(emptyList<Long>(), sleeps.toList())
        assertEquals(ObjectLocalState.MISSING, row(refused).localState)
    }

    @Test
    fun aDownloadWhoseSizeDoesNotMatchIsDeletedAndTheObjectStaysMissing() {
        val objectId = id("t", 1)
        addObject(objectId, 2_000)
        bucketFailures[objectId] = mutableListOf("wrongSize")
        assertThrows(DownloadSizeMismatchError::class.java) { queue().fetch(objectId, DownloadPriority.VISIBLE) }
        assertEquals(1, urlRequests.getValue(objectId).get())
        assertNull(files.size(objectId))
        assertFalse(files.paths.partialFile(objectId).exists())
        assertEquals(ObjectLocalState.MISSING, row(objectId).localState)
    }

    @Test
    fun aPresentObjectIsNotDownloadedAgain() {
        val objectId = id("o", 1)
        addObject(objectId, 100, ObjectLocalState.PRESENT)
        queue().fetch(objectId, DownloadPriority.OPENED)
        assertNull(urlRequests[objectId])
    }

    @Test
    fun thumbnailsRunSixAtATimeAndOriginalsTwo() {
        bucketDelayMs = 150
        val thumbs = (1..10).map { id("t", it).also { objectId -> addObject(objectId, 500) } }
        val originals = (1..5).map { id("o", it).also { objectId -> addObject(objectId, 500) } }
        val queue = queue()
        (thumbs + originals).map { objectId -> async { queue.fetch(objectId, DownloadPriority.VISIBLE) } }.forEach { it.get(30, TimeUnit.SECONDS) }
        assertEquals(6, maxInFlight.getValue(ObjectVariant.THUMBNAIL).get())
        assertEquals(2, maxInFlight.getValue(ObjectVariant.ORIGINAL).get())
        assertEquals(6, MediaConstants.THUMBNAIL_FETCH_CONCURRENCY)
        assertEquals(2, MediaConstants.ORIGINAL_FETCH_CONCURRENCY)
        assertEquals(600_000L, MediaConstants.DOWNLOAD_TIMEOUT_MS)
    }

    @Test
    fun openedJumpsAheadOfQueuedWorkAndAskingAgainRaisesPriority() {
        val busy = (1..2).map { id("o", it).also { objectId -> addObject(objectId, 100) } }
        val background = id("o", 3).also { addObject(it, 100) }
        val visible = id("o", 4).also { addObject(it, 100) }
        val opened = id("o", 5).also { addObject(it, 100) }
        val raised = id("o", 6).also { addObject(it, 100) }
        bucketDelays[raised] = 300
        val queue = queue()
        gate = CountDownLatch(1)
        val running = busy.map { objectId -> async { queue.fetch(objectId, DownloadPriority.VISIBLE) } }
        while (bucketOrder.size < 2) Thread.sleep(10)
        val waiting = listOf(
            async { queue.fetch(background, DownloadPriority.BACKGROUND) },
            async { queue.fetch(raised, DownloadPriority.BACKGROUND) },
        )
        waitUntil { queue.waiting() == mapOf(background to DownloadPriority.BACKGROUND, raised to DownloadPriority.BACKGROUND) }
        val more = listOf(async { queue.fetch(visible, DownloadPriority.VISIBLE) })
        waitUntil { visible in queue.waiting() }
        val top = listOf(async { queue.fetch(opened, DownloadPriority.OPENED) }, async { queue.fetch(raised, DownloadPriority.OPENED) })
        waitUntil {
            queue.waiting() == mapOf(
                background to DownloadPriority.BACKGROUND,
                raised to DownloadPriority.OPENED,
                visible to DownloadPriority.VISIBLE,
                opened to DownloadPriority.OPENED,
            )
        }
        gate!!.countDown()
        (running + waiting + more + top).forEach { it.get(20, TimeUnit.SECONDS) }
        val after = bucketOrder.drop(2)
        assertEquals(setOf(opened, raised), after.take(2).toSet())
        assertEquals(listOf(visible, background), after.drop(2))
    }

    @Test
    fun backgroundWorkWaitsWhileOfflineUntilResume() {
        val objectId = id("t", 1)
        addObject(objectId, 100)
        offline = true
        val queue = queue()
        val pending = async { queue.fetch(objectId, DownloadPriority.BACKGROUND) }
        Thread.sleep(300)
        assertNull(urlRequests[objectId])
        assertFalse(pending.isDone)
        queue.fetch(id("t", 2).also { addObject(it, 100) }, DownloadPriority.VISIBLE)
        offline = false
        queue.resume()
        pending.get(10, TimeUnit.SECONDS)
        assertEquals(ObjectLocalState.PRESENT, row(objectId).localState)
    }

    @Test
    fun cancelBackgroundRejectsQueuedBackgroundWorkOnly() {
        val background = id("t", 1).also { addObject(it, 100) }
        val visible = id("t", 2).also { addObject(it, 100) }
        offline = true
        val queue = queue()
        val waiting = async { queue.fetch(background, DownloadPriority.BACKGROUND) }
        Thread.sleep(200)
        queue.cancelBackground()
        val failure = runCatching { waiting.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue("$failure", failure?.cause is DownloadCancelledError)
        queue.fetch(visible, DownloadPriority.VISIBLE)
        assertEquals(ObjectLocalState.PRESENT, row(visible).localState)
        assertEquals(ObjectLocalState.MISSING, row(background).localState)
    }

    @Test
    fun abortAllCancelsRunningDownloadsAndLeavesNoPartialFile() {
        val objectId = id("o", 1)
        addObject(objectId, 50_000)
        gate = CountDownLatch(1)
        val queue = queue()
        val running = async { queue.fetch(objectId, DownloadPriority.OPENED) }
        while (bucketOrder.isEmpty()) Thread.sleep(10)
        queue.abortAll()
        val failure = runCatching { running.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue("$failure", failure?.cause is DownloadCancelledError)
        gate!!.countDown()
        assertEquals(ObjectLocalState.MISSING, row(objectId).localState)
        assertNull(files.size(objectId))
        assertFalse(files.paths.partialFile(objectId).exists())
        assertEquals(emptyList<Long>(), sleeps.toList())
    }

    @Test
    fun progressReportsReceivedBytesUpToTheObjectSize() {
        val objectId = id("o", 1)
        addObject(objectId, 300_000)
        val queue = queue()
        val seen = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val unsubscribe = queue.onProgress(objectId) { received, total -> seen += received to total }
        queue.fetch(objectId, DownloadPriority.OPENED)
        unsubscribe()
        assertTrue(seen.isNotEmpty())
        assertEquals(300_000L to 300_000L, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun originalCacheOpenRetainsFetchesWhenMissingAndEnforcesTheCap() {
        val old = id("o", 1).also { addObject(it, 3_000, ObjectLocalState.PRESENT) }
        files.writeObject(old, sequenceOf(bucket.getValue(old)))
        val wanted = id("o", 2).also { addObject(it, 3_000) }
        val pending = id("o", 3).also { addObject(it, 3_000) }
        database.transaction { tx -> tx.objects.put(tx.objects.get(pending)!!.copy(uploadState = ObjectUploadState.QUEUED)) }
        val queue = queue()
        val cache = OriginalCache(database, files, capBytes = { 4_000 }, localOnly = { false }, isWifi = { true }, fetch = queue::fetch, now = { clock })
        val handle = cache.open("m" + wanted.drop(1))
        assertNotNull(handle)
        assertEquals(wanted, handle!!.objectId)
        assertEquals(ObjectLocalState.PRESENT, row(wanted).localState)
        assertEquals(ObjectLocalState.MISSING, row(old).localState)
        cache.enforceCap()
        assertEquals(ObjectLocalState.PRESENT, row(wanted).localState)
        handle.release()
        assertNull(cache.open("m" + pending.drop(1)))
        assertNull(cache.open("m" + id("o", 9).drop(1)))
        assertTrue(File(files.paths.objectFile(wanted).path).isFile)
    }
}
