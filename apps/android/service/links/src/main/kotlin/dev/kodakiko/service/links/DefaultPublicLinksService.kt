package dev.kodakiko.service.links

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.COLLECTION_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.COLLECTION_NAME_MAX_CHARS
import dev.kodakiko.contract.CollectionCreateRequest
import dev.kodakiko.contract.CollectionKind
import dev.kodakiko.contract.CollectionMetadataV1
import dev.kodakiko.contract.ContractSchema
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.EntryCreateRequest
import dev.kodakiko.contract.EntryMediaRequest
import dev.kodakiko.contract.EntryMembershipRequest
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.EntryPurpose
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.contract.openPayload
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.KdfContext
import dev.kodakiko.crypto.KdfSubkeyIds
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.crypto.createStreamEncryptor
import dev.kodakiko.crypto.streamLayout
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wipe
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.media.decryptWhole
import dev.kodakiko.data.net.ApiCall
import dev.kodakiko.data.net.LinkCalls
import dev.kodakiko.data.net.SharingCalls
import dev.kodakiko.data.net.SyncCalls
import dev.kodakiko.js.jsTrim
import dev.kodakiko.media.UnsupportedVideoError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.kodakiko.contract.ObjectVariant as WireObjectVariant

private class SourceObjects(val original: ObjectRow, val thumbnail: ObjectRow)

private class CopiedMedia(val descriptor: MediaDescriptorV1, val mediaKey: ByteArray, val objects: List<LinkObject>)

private class LinkKeys(val secret: ByteArray, val linkKey: ByteArray, val accessToken: ByteArray) {
    fun wipeAll() {
        wipe(secret)
        wipe(linkKey)
        wipe(accessToken)
    }
}

private class SkipCounts {
    var entries = LinksConstants.NO_SKIPS
    var undecodable = LinksConstants.NO_SKIPS
    var unsupportedVideo = LinksConstants.NO_SKIPS
}

private fun <T> validated(schema: ContractSchema<T>, value: T): T = schema.decode(schema.encode(value))

private fun binding(parentId: String, childId: String): String = parentId + LinksConstants.KEY_BINDING_SEPARATOR + childId

private fun ciphertextSize(plaintextSize: Long): Long = streamLayout(plaintextSize, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize

class DefaultPublicLinksService(private val deps: PublicLinksServiceDeps) : PublicLinksService {
    private val sodium = deps.sodium
    private val files = deps.files

    override suspend fun create(input: CreateLinkInput, onProgress: (CreateLinkProgress) -> Unit): CreateLinkResult {
        val account = requireAccount()
        val metadata = validate(input)
        val keys = deriveLinkKeys(sodium.randomBytes(LinksConstants.LINK_SECRET_BYTES))
        val collectionId = deps.randomId()
        val pendingObjects = mutableListOf<LinkObject>()
        var created = false
        try {
            val keyByOwner = wrapKey(sodium, account.masterKey, keys.secret, AdContext.LINK_SECRET_BY_MASTER, collectionId)
            val sealedName = sealPayload(
                sodium,
                keys.linkKey,
                CollectionMetadataV1(jsTrim(input.title), deps.clock.nowIso()),
                ContractSchemas.collectionMetadataV1,
                AdContext.COLLECTION_METADATA,
                collectionId,
            )
            request(SharingCalls.create(CollectionCreateRequest(collectionId, CollectionKind.LINK, encodeBytes(keyByOwner), encodeBytes(sealedName))))
            created = true
            val skips = SkipCounts()
            for ((done, entryId) in input.entryIds.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(CreateLinkProgress(CreateLinkStep.PREPARING, done, input.entryIds.size, LinksConstants.NO_BYTES, LinksConstants.NO_BYTES))
                pendingObjects += copyEntry(input, metadata[entryId], entryId, collectionId, keys.linkKey, skips)
            }
            if (pendingObjects.isEmpty()) throw LinkInputException(LinkInputReason.NOTHING)
            val bytesTotal = pendingObjects.sumOf { it.sizeBytes }
            val uploading = { bytesDone: Long ->
                onProgress(CreateLinkProgress(CreateLinkStep.UPLOADING, LinksConstants.FIRST_STEP, pendingObjects.size, bytesDone, bytesTotal))
            }
            uploading(LinksConstants.NO_BYTES)
            uploadAll(LinkUploadDeps(deps.http, deps.uploader, files, deps.ioDispatcher), pendingObjects, uploading)
            onProgress(CreateLinkProgress(CreateLinkStep.REGISTERING, LinksConstants.FIRST_STEP, LinksConstants.REGISTERING_TOTAL, bytesTotal, bytesTotal))
            val link = request(LinkCalls.create(collectionId, encodeBytes(keys.accessToken), input.expiry))
            return CreateLinkResult(
                url = buildLinkUrl(deps.baseUrl(), link.linkId, keys.secret),
                linkId = link.linkId,
                skippedEntries = skips.entries,
                skippedUndecodable = skips.undecodable,
                skippedUnsupportedVideo = skips.unsupportedVideo,
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (created) quietly { request(SharingCalls.remove(collectionId)) }
                for (linkObject in pendingObjects) quietly { io { files.delete(linkObject.id) } }
            }
            throw failure
        } finally {
            keys.wipeAll()
        }
    }

    override suspend fun list(): List<OwnerLink> {
        val account = requireAccount()
        val base = deps.baseUrl()
        val items = request(LinkCalls.list()).items
        val links = mutableListOf<OwnerLink>()
        for (item in items) {
            val collection = request(SyncCalls.collection(item.collectionId))
            val secret = unwrapKey(sodium, account.masterKey, decodeBytes(collection.keyMaterial), AdContext.LINK_SECRET_BY_MASTER, collection.id)
            val linkKey = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.LINK_COLLECTION_KEY, KdfContext.LINK.value, secret)
            try {
                val title = openPayload(
                    sodium,
                    linkKey,
                    decodeBytes(collection.metadataBlob),
                    ContractSchemas.collectionMetadataV1,
                    COLLECTION_METADATA_SCHEMA_VERSION,
                    emptyMap(),
                    AdContext.COLLECTION_METADATA,
                    collection.id,
                ).name
                links += OwnerLink(
                    linkId = item.linkId,
                    url = buildLinkUrl(base, item.linkId, secret),
                    title = title,
                    expiresAt = item.expiresAt,
                    maxViews = item.maxViews,
                    viewCount = item.viewCount,
                    createdAt = item.createdAt,
                )
            } finally {
                wipe(secret)
                wipe(linkKey)
            }
        }
        return links
    }

    override suspend fun revoke(linkId: String) {
        request(LinkCalls.revoke(linkId))
    }

    private fun requireAccount(): AccountKeys = deps.account() ?: throw LinkInputException(LinkInputReason.LOCKED)

    private suspend fun <T> io(work: suspend () -> T): T = withContext(deps.ioDispatcher) { work() }

    private suspend fun <T> request(call: ApiCall<T>): T = io { deps.http.request(call) }

    private suspend fun quietly(work: suspend () -> Unit) {
        try {
            work()
        } catch (_: Exception) {
            return
        }
    }

    private fun deriveLinkKeys(secret: ByteArray): LinkKeys =
        LinkKeys(
            secret = secret,
            linkKey = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.LINK_COLLECTION_KEY, KdfContext.LINK.value, secret),
            accessToken = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.LINK_ACCESS_TOKEN, KdfContext.LINK.value, secret),
        )

    private suspend fun validate(input: CreateLinkInput): Map<String, EntryMetadataV1> {
        if (input.entryIds.isEmpty() || input.entryIds.size > LinksConstants.MAX_ENTRIES) throw LinkInputException(LinkInputReason.ENTRIES)
        if (input.mediaIds.isEmpty()) throw LinkInputException(LinkInputReason.MEDIA)
        val title = jsTrim(input.title)
        if (title.isEmpty() || title.length > COLLECTION_NAME_MAX_CHARS) throw LinkInputException(LinkInputReason.TITLE)
        val metadata = LinkedHashMap<String, EntryMetadataV1>()
        val allMedia = HashSet<String>()
        for (entryId in input.entryIds) {
            val row = io { deps.rowStore.transaction { tx -> tx.entries.get(entryId) } }
            if (row == null || row.trashedAt != null) throw LinkInputException(LinkInputReason.ENTRY)
            val meta = deps.readMetadata(entryId)
            meta.media.forEach { allMedia += it.mediaId }
            metadata[entryId] = meta
        }
        if (input.mediaIds.any { it !in allMedia }) throw LinkInputException(LinkInputReason.MEDIA)
        return metadata
    }

    private suspend fun copyEntry(
        input: CreateLinkInput,
        meta: EntryMetadataV1?,
        entryId: String,
        collectionId: String,
        linkKey: ByteArray,
        skips: SkipCounts,
    ): List<LinkObject> {
        val selected = meta?.media?.filter { it.mediaId in input.mediaIds }.orEmpty()
        if (meta == null || selected.isEmpty()) return emptyList()
        val copies = mutableListOf<CopiedMedia>()
        try {
            for (media in selected) {
                try {
                    copies += copyMedia(entryId, media)
                } catch (_: StripUndecodableException) {
                    skips.undecodable += 1
                } catch (_: UnsupportedVideoError) {
                    skips.unsupportedVideo += 1
                }
            }
            if (copies.isEmpty()) {
                skips.entries += 1
                return emptyList()
            }
            registerCopy(input, meta, collectionId, linkKey, copies)
            return copies.flatMap { it.objects }
        } finally {
            copies.forEach { wipe(it.mediaKey) }
        }
    }

    private suspend fun registerCopy(input: CreateLinkInput, meta: EntryMetadataV1, collectionId: String, linkKey: ByteArray, copies: List<CopiedMedia>) {
        val copyId = deps.randomId()
        val entryKey = sodium.randomBytes(KEY_BYTES)
        try {
            val now = deps.clock.nowIso()
            val copyMetadata = validated(
                ContractSchemas.entryMetadataV1,
                meta.copy(
                    timeZone = null,
                    note = if (input.includeNotes) meta.note else LinksConstants.EMPTY_NOTE,
                    tags = emptyList(),
                    location = null,
                    media = copies.map { it.descriptor },
                    createdAtUtc = now,
                    editedAtUtc = now,
                ),
            )
            request(
                SyncCalls.createEntry(
                    EntryCreateRequest(
                        id = copyId,
                        purpose = EntryPurpose.LINK_COPY,
                        metadataBlob = encodeBytes(sealPayload(sodium, entryKey, copyMetadata, ContractSchemas.entryMetadataV1, AdContext.ENTRY_METADATA, copyId)),
                        memberships = listOf(
                            EntryMembershipRequest(
                                collectionId,
                                encodeBytes(wrapKey(sodium, linkKey, entryKey, AdContext.ENTRY_KEY_BY_COLLECTION, binding(collectionId, copyId))),
                            ),
                        ),
                        media = copies.map { copy ->
                            val mediaId = copy.descriptor.mediaId
                            EntryMediaRequest(mediaId, encodeBytes(wrapKey(sodium, entryKey, copy.mediaKey, AdContext.MEDIA_KEY_BY_ENTRY, binding(copyId, mediaId))))
                        },
                    ),
                ),
            )
        } finally {
            wipe(entryKey)
        }
    }

    private suspend fun objectsOf(mediaId: String): SourceObjects {
        val rows = io { deps.rowStore.transaction { tx -> tx.objects.byMedia(mediaId) } }
        val original = rows.firstOrNull { it.variant == ObjectVariant.ORIGINAL }
        val thumbnail = rows.firstOrNull { it.variant == ObjectVariant.THUMBNAIL }
        if (original == null || thumbnail == null) throw LinkInputException(LinkInputReason.OBJECTS)
        return SourceObjects(original, thumbnail)
    }

    private suspend fun copyMedia(entryId: String, source: MediaDescriptorV1): CopiedMedia {
        val objects = objectsOf(source.mediaId)
        deps.fetchObject(objects.original.id)
        deps.fetchObject(objects.thumbnail.id)
        val originalKey = deps.entryKeys.variantKey(entryId, source.mediaId, ObjectVariant.ORIGINAL)
        val thumbnailKey = deps.entryKeys.variantKey(entryId, source.mediaId, ObjectVariant.THUMBNAIL)
        val mediaId = deps.randomId()
        val mediaKey = sodium.randomBytes(KEY_BYTES)
        val newOriginalKey = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.MEDIA_ORIGINAL, KdfContext.MEDIA.value, mediaKey)
        val newThumbnailKey = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.MEDIA_THUMBNAIL, KdfContext.MEDIA.value, mediaKey)
        val originalObjectId = deps.randomId()
        val thumbnailObjectId = deps.randomId()
        try {
            val stripped = io {
                openDecryptingByteSource(sodium, files, objects.original.id, originalKey, objects.original.plaintextSize).use { plainSource ->
                    val copy = deps.stripper.strip(plainSource, source)
                    files.writeObject(originalObjectId, encryptChunks(newOriginalKey, copy.chunks, copy.plaintextSize))
                    copy
                }
            }
            val thumbBytes = io {
                val ciphertext = files.readRange(objects.thumbnail.id, 0, Math.toIntExact(objects.thumbnail.sizeBytes))
                decryptWhole(sodium, thumbnailKey, ciphertext, objects.thumbnail.plaintextSize)
            }
            val thumbSize = thumbBytes.size.toLong()
            try {
                io { files.writeObject(thumbnailObjectId, encryptChunks(newThumbnailKey, sequenceOf(thumbBytes), thumbSize)) }
            } finally {
                wipe(thumbBytes)
            }
            val descriptor = validated(
                ContractSchemas.mediaDescriptorV1,
                source.copy(
                    mediaId = mediaId,
                    mimeType = stripped.mimeType,
                    width = stripped.width,
                    height = stripped.height,
                    durationMs = stripped.durationMs,
                    originalPlaintextBytes = stripped.plaintextSize,
                    thumbnail = source.thumbnail.copy(plaintextBytes = thumbSize),
                    capturedAtUtc = null,
                    originalFileName = null,
                ),
            )
            return CopiedMedia(
                descriptor = descriptor,
                mediaKey = mediaKey,
                objects = listOf(
                    LinkObject(originalObjectId, mediaId, WireObjectVariant.ORIGINAL, ciphertextSize(stripped.plaintextSize)),
                    LinkObject(thumbnailObjectId, mediaId, WireObjectVariant.THUMBNAIL, ciphertextSize(thumbSize)),
                ),
            )
        } catch (failure: Throwable) {
            wipe(mediaKey)
            withContext(NonCancellable) {
                quietly { io { files.delete(originalObjectId) } }
                quietly { io { files.delete(thumbnailObjectId) } }
            }
            throw failure
        } finally {
            wipe(originalKey)
            wipe(thumbnailKey)
            wipe(newOriginalKey)
            wipe(newThumbnailKey)
        }
    }

    private fun encryptChunks(key: ByteArray, chunks: Sequence<ByteArray>, plaintextSize: Long): Sequence<ByteArray> =
        sequence {
            val encryptor = createStreamEncryptor(sodium, key, plaintextSize)
            yield(encryptor.header)
            val chunkSize = encryptor.layout.chunkSize
            var buffer = ByteArray(0)
            for (chunk in chunks) {
                buffer += chunk
                while (buffer.size >= chunkSize && !encryptor.isDone()) {
                    yield(encryptor.encryptNext(buffer.copyOfRange(0, chunkSize)))
                    buffer = buffer.copyOfRange(chunkSize, buffer.size)
                }
            }
            if (!encryptor.isDone()) yield(encryptor.encryptNext(buffer))
        }
}
