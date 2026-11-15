package dev.kodakiko.data.net

import dev.kodakiko.contract.CollectionDto
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.DiffPage
import dev.kodakiko.contract.EntryCreateRequest
import dev.kodakiko.contract.EntryDto
import dev.kodakiko.contract.EntryPatchRequest
import dev.kodakiko.contract.MediaCreateRequest
import dev.kodakiko.contract.MediaDto
import dev.kodakiko.contract.ObjectDto
import dev.kodakiko.contract.PartsResponse
import dev.kodakiko.contract.PresignRequest
import dev.kodakiko.contract.PresignResponse
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.SyncStateResponse
import kotlinx.serialization.json.JsonElement

object SyncCalls {
    fun syncState(): ApiCall<SyncStateResponse> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.syncState(),
        body = null,
        responseSchema = ContractSchemas.syncStateResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun collection(id: String): ApiCall<CollectionDto> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.collection(id),
        body = null,
        responseSchema = ContractSchemas.collectionDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun diff(id: String, since: Long, limit: Long): ApiCall<DiffPage> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.collectionDiff(id) + HttpKeys.QUERY_START + HttpKeys.SINCE_PARAMETER + since +
            HttpKeys.QUERY_SEPARATOR + HttpKeys.LIMIT_PARAMETER + limit,
        body = null,
        responseSchema = ContractSchemas.diffPage,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun createEntry(body: EntryCreateRequest): ApiCall<EntryDto> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.entries(),
        body = ContractSchemas.entryCreateRequest.encode(body),
        responseSchema = ContractSchemas.entryDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun getEntry(id: String): ApiCall<EntryDto> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.entry(id),
        body = null,
        responseSchema = ContractSchemas.entryDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun patchEntry(id: String, body: EntryPatchRequest): ApiCall<EntryDto> = ApiCall(
        method = HttpKeys.PATCH,
        path = Routes.entry(id),
        body = ContractSchemas.entryPatchRequest.encode(body),
        responseSchema = ContractSchemas.entryDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun trashEntry(id: String): ApiCall<EntryDto> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.entryTrash(id),
        body = null,
        responseSchema = ContractSchemas.entryDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun restoreEntry(id: String): ApiCall<EntryDto> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.entryRestore(id),
        body = null,
        responseSchema = ContractSchemas.entryDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun deleteEntry(id: String): ApiCall<JsonElement?> = ApiCall(
        method = HttpKeys.DELETE,
        path = Routes.entry(id),
        body = null,
        responseSchema = UnknownResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun addMedia(entryId: String, body: MediaCreateRequest): ApiCall<MediaDto> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.entryMedia(entryId),
        body = ContractSchemas.mediaCreateRequest.encode(body),
        responseSchema = ContractSchemas.mediaDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun removeMedia(mediaId: String): ApiCall<JsonElement?> = ApiCall(
        method = HttpKeys.DELETE,
        path = Routes.media(mediaId),
        body = null,
        responseSchema = UnknownResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun presign(body: PresignRequest): ApiCall<PresignResponse> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.objectsPresign(),
        body = ContractSchemas.presignRequest.encode(body),
        responseSchema = ContractSchemas.presignResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun parts(objectId: String): ApiCall<PartsResponse> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.objectParts(objectId),
        body = null,
        responseSchema = ContractSchemas.partsResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun confirm(objectId: String): ApiCall<ObjectDto> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.objectConfirm(objectId),
        body = null,
        responseSchema = ContractSchemas.objectDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )
}
