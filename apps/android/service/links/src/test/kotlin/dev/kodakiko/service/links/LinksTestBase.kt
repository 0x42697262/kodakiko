@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.links

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.COLLECTION_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.CollectionCreateRequest
import dev.kodakiko.contract.CollectionDto
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ENTRY_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.EntryCreateRequest
import dev.kodakiko.contract.EntryDto
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.contract.ObjectDto
import dev.kodakiko.contract.ObjectState
import dev.kodakiko.contract.PresignRequest
import dev.kodakiko.contract.PresignResponse
import dev.kodakiko.contract.PublicLinkDto
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.openPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.KdfContext
import dev.kodakiko.crypto.KdfSubkeyIds
import dev.kodakiko.crypto.unwrapKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import dev.kodakiko.contract.CollectionKind as WireCollectionKind
import dev.kodakiko.contract.CollectionRole as WireCollectionRole
import dev.kodakiko.contract.EntryPurpose as WireEntryPurpose
import dev.kodakiko.contract.ObjectVariant as WireObjectVariant

const val BASE_URL = "https://journal.example"
const val NOW = "2026-06-01T10:00:00.000Z"
val LINK_ID = "L".repeat(22)
val LINK_OWNER_ID = "O".repeat(22)
val CONFIRMED_MEDIA_ID = "M".repeat(22)
val CONFIRM_PATTERN = Regex("/objects/[^/]+/confirm$")
val COLLECTION_PATTERN = Regex("/collections/[^/]+$")

fun passThroughReencoder(): PhotoReencoder = PhotoReencoder { bytes: ByteArray, _: String -> ReencodedPhoto(bytes.copyOf(), 100, 100) }

fun fragmentOf(url: String): String = "#" + URI(url).rawFragment

open class LinksTestBase : HarnessTestBase() {
    protected lateinit var harness: EntriesHarness
    protected lateinit var fake: FakeHttp
    protected lateinit var uploader: FakeUploader
    protected lateinit var stripper: LinkStripper
    protected lateinit var service: PublicLinksService
    protected lateinit var io: CoroutineDispatcher
    protected val fetched: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    protected var signedIn: Boolean = true

    protected fun linksTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            io = UnconfinedTestDispatcher(testScheduler)
            harness = EntriesHarness(context, sodium, createAccountFixture(), io)
            fake = FakeHttp()
            uploader = FakeUploader(backgroundScope, harness.fixture.fileStore)
            stripper = DefaultLinkStripper(passThroughReencoder())
            serverRoutes()
            service = createService()
            body()
        }

    protected fun createService(custom: LinkStripper? = null): PublicLinksService {
        val account: () -> AccountKeys? = { if (signedIn) harness.fixture.keys else null }
        val readMetadata: suspend (String) -> EntryMetadataV1 = { entryId: String -> harness.service.readMetadata(entryId) }
        val fetchObject: suspend (String) -> Unit = { objectId: String -> fetched += objectId }
        val baseUrl: suspend () -> String = { BASE_URL }
        val files: LinkFiles = FileStoreLinkFiles { harness.fixture.fileStore }
        return DefaultPublicLinksService(
            PublicLinksServiceDeps(
                http = fake,
                sodium = sodium,
                account = account,
                readMetadata = readMetadata,
                entryKeys = harness.entryKeys,
                rowStore = harness.fixture.rowStore,
                files = files,
                fetchObject = fetchObject,
                stripper = custom ?: stripper,
                uploader = uploader,
                baseUrl = baseUrl,
                randomId = harness.fixture.randomId,
                clock = harness.clock,
                ioDispatcher = io,
            ),
        )
    }

    private fun serverRoutes() {
        fake.on(POST, Routes.collections()) { request: RecordedCall ->
            val body = ContractSchemas.collectionCreateRequest.decode(request.json())
            ContractSchemas.collectionDto.encode(
                CollectionDto(
                    id = body.id,
                    kind = WireCollectionKind.LINK,
                    role = WireCollectionRole.OWNER,
                    ownerId = LINK_OWNER_ID,
                    ownerUsername = "ada",
                    keyMaterial = body.keyByOwner,
                    metadataBlob = body.metadataBlob,
                    lastSeq = 0,
                    createdAt = NOW,
                    metaUpdatedAt = NOW,
                    recipientCount = null,
                ),
            )
        }
        fake.on(POST, Routes.entries()) { request: RecordedCall ->
            val body = ContractSchemas.entryCreateRequest.decode(request.json())
            ContractSchemas.entryDto.encode(
                EntryDto(
                    id = body.id,
                    ownerId = LINK_OWNER_ID,
                    purpose = WireEntryPurpose.LINK_COPY,
                    revision = 1,
                    metadataBlob = body.metadataBlob,
                    trashedAt = null,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
        }
        fake.on(POST, Routes.objectsPresign()) { request: RecordedCall ->
            val body = ContractSchemas.presignRequest.decode(request.json())
            ContractSchemas.presignResponse.encode(PresignResponse.Single("https://bucket.example/${body.id}", NOW))
        }
        fake.on(POST, CONFIRM_PATTERN) { request: RecordedCall ->
            val id = request.path.split("/").let { it[it.size - 2] }
            ContractSchemas.objectDto.encode(
                ObjectDto(id, CONFIRMED_MEDIA_ID, WireObjectVariant.ORIGINAL, uploader.uploaded[id]?.size?.toLong() ?: 1L, ObjectState.UPLOADED),
            )
        }
        fake.on(POST, Routes.publicLinks()) { request: RecordedCall ->
            val body = ContractSchemas.publicLinkCreateRequest.decode(request.json())
            ContractSchemas.publicLinkDto.encode(PublicLinkDto(LINK_ID, body.collectionId, null, null, 0, NOW))
        }
        fake.on(DELETE, COLLECTION_PATTERN) { null }
    }

    protected fun requestsOf(method: String, path: String): List<RecordedCall> = fake.find(method, path)

    protected fun collectionRequest(): CollectionCreateRequest =
        ContractSchemas.collectionCreateRequest.decode(requestsOf(POST, Routes.collections()).first().json())

    protected fun entryRequests(): List<EntryCreateRequest> = requestsOf(POST, Routes.entries()).map { ContractSchemas.entryCreateRequest.decode(it.json()) }

    protected fun presignRequests(): List<PresignRequest> = requestsOf(POST, Routes.objectsPresign()).map { ContractSchemas.presignRequest.decode(it.json()) }

    protected fun linkSecretOf(collection: CollectionCreateRequest): ByteArray =
        unwrapKey(sodium, harness.fixture.keys.masterKey, decodeBytes(collection.keyByOwner), AdContext.LINK_SECRET_BY_MASTER, collection.id)

    protected fun linkKeyOf(secret: ByteArray): ByteArray = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.LINK_COLLECTION_KEY, KdfContext.LINK.value, secret)

    protected fun openCopyMetadata(entryKey: ByteArray, copyId: String, blob: String): EntryMetadataV1 =
        openPayload(sodium, entryKey, decodeBytes(blob), ContractSchemas.entryMetadataV1, ENTRY_METADATA_SCHEMA_VERSION, emptyMap(), AdContext.ENTRY_METADATA, copyId)

    protected fun openCollectionTitle(linkKey: ByteArray, collectionId: String, blob: String): String =
        openPayload(
            sodium,
            linkKey,
            decodeBytes(blob),
            ContractSchemas.collectionMetadataV1,
            COLLECTION_METADATA_SCHEMA_VERSION,
            emptyMap(),
            AdContext.COLLECTION_METADATA,
            collectionId,
        ).name

    protected fun copyEntryKey(linkKey: ByteArray, collectionId: String, copy: EntryCreateRequest): ByteArray =
        unwrapKey(sodium, linkKey, decodeBytes(copy.memberships.first().entryKeyByCollection), AdContext.ENTRY_KEY_BY_COLLECTION, "$collectionId/${copy.id}")

    protected suspend fun syncedEntry(media: List<ByteArray>, note: String = "secret picnic", tags: List<String> = listOf("family")): String {
        val entryId = harness.createEntry(media, note, tags)
        harness.markSynced(entryId)
        return entryId
    }

    protected fun input(
        entryIds: List<String>,
        mediaIds: Set<String>,
        title: String = "x",
        expiry: LinkExpiry = LinkExpiry.SEVEN_DAYS,
        includeNotes: Boolean = false,
    ): CreateLinkInput = CreateLinkInput(entryIds = entryIds, mediaIds = mediaIds, title = title, expiry = expiry, includeNotes = includeNotes)

    protected fun localObjectIds(): Set<String> = harness.fixture.fileStore.listSizes().keys
}
