@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sync

import dev.kodakiko.contract.ContractJson
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ErrorBody
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.ErrorResponse
import dev.kodakiko.contract.PartUrl
import dev.kodakiko.contract.PartsResponse
import dev.kodakiko.contract.PresignResponse
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.UploadedPart
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.data.upload.UploadPart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private const val LOOPBACK = "127.0.0.1"
private const val SESSION_TOKEN = "session-token"
private const val WIRE_EXPIRES_AT = "2026-11-13T10:00:00.000Z"

private fun jsonResponse(status: Int, element: JsonElement): MockResponse =
    MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(ContractJson.stringify(element)).build()

private fun errorResponse(status: Int, code: ErrorCode): MockResponse =
    jsonResponse(status, ContractSchemas.errorResponseSchema.encode(ErrorResponse(ErrorBody(code, code.wire, "req-fake", JsonNull))))

@RunWith(RobolectricTestRunner::class)
class UploadResumeWireTest : SyncTestBase() {
    private val server = MockWebServer()

    @After
    fun stopServer() = server.close()

    @Test
    fun resumesAMultipartUploadThroughTheRealHttpClientWhenConfirmAnswersUploadIncomplete() = runTest {
        val fixture = createAccountFixture()
        val local = createLocalEntry(fixture, listOf(ByteArray(100)), "n", emptyList())
        val objectId = local.originalObjectIds[0]
        val size = fixture.rowStore.transaction { it.objects.get(objectId) }?.sizeBytes ?: error("missing object")
        val partSize = (size + 2) / 3
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when ("${request.method} ${request.url.encodedPath}") {
                    "$POST ${Routes.objectsPresign()}" -> jsonResponse(
                        200,
                        ContractSchemas.presignResponse.encode(
                            PresignResponse.Multipart(partSize, listOf(1L, 2L, 3L).map { PartUrl(it, "https://bucket.test/p$it") }, WIRE_EXPIRES_AT),
                        ),
                    )
                    "$POST ${Routes.objectConfirm(objectId)}" -> errorResponse(422, ErrorCode.UPLOAD_INCOMPLETE)
                    "$GET ${Routes.objectParts(objectId)}" -> jsonResponse(
                        200,
                        ContractSchemas.partsResponse.encode(
                            PartsResponse(
                                partSize,
                                listOf(UploadedPart(1, partSize)),
                                listOf(PartUrl(2, "https://bucket.test/p2"), PartUrl(3, "https://bucket.test/p3")),
                                WIRE_EXPIRES_AT,
                            ),
                        ),
                    )
                    else -> errorResponse(404, ErrorCode.NOT_FOUND)
                }
        }
        server.start(InetAddress.getByName(LOOPBACK), 0)
        val http = HttpClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            sessionToken = { SESSION_TOKEN },
            onSessionInvalid = {},
            sleep = {},
            okHttpClient = OkHttpClient(),
        ).asSyncHttp()
        val uploader = FakeUploader()
        val uploads = DefaultUploadCoordinator(
            UploadCoordinatorDeps(
                http = http,
                rowStore = fixture.rowStore,
                uploader = uploader,
                onEntriesChanged = {},
                uploadPolicy = UploadPolicy { true },
                thumbnails = ThumbnailUploadHook { _: String -> },
                notificationPrompt = NotificationPromptPort { },
                log = noLog(),
                scope = backgroundScope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ),
        )

        uploads.enqueueObjectUploads(listOf(objectId))
        uploader.jobs.clear()
        uploads.handleEvent(UploadEvent.Completed(objectId))

        assertEquals(
            listOf(
                ObjectUploadJob(
                    objectId,
                    listOf(
                        UploadPart(2, "https://bucket.test/p2", partSize, partSize),
                        UploadPart(3, "https://bucket.test/p3", partSize * 2, size - partSize * 2),
                    ),
                ),
            ),
            uploader.jobs,
        )
        assertEquals(ObjectUploadState.UPLOADING, fixture.rowStore.transaction { it.objects.get(objectId) }?.uploadState)
        val presign = server.takeRequest(1, TimeUnit.SECONDS) ?: error("no presign request")
        assertEquals("$POST ${Routes.objectsPresign()}", "${presign.method} ${presign.url.encodedPath}")
        assertEquals("Bearer $SESSION_TOKEN", presign.headers["Authorization"])
        assertEquals(
            buildJsonObject {
                put("id", objectId)
                put("mediaId", local.mediaIds[0])
                put("variant", "original")
                put("sizeBytes", size)
            },
            ContractJson.parse(presign.body?.utf8() ?: error("no presign body")),
        )
        val confirm = server.takeRequest(1, TimeUnit.SECONDS) ?: error("no confirm request")
        assertEquals("$POST ${Routes.objectConfirm(objectId)}", "${confirm.method} ${confirm.url.encodedPath}")
        val parts = server.takeRequest(1, TimeUnit.SECONDS) ?: error("no parts request")
        assertEquals("$GET ${Routes.objectParts(objectId)}", "${parts.method} ${parts.url.encodedPath}")
        assertEquals(3, server.requestCount)
    }
}
