package dev.kodakiko.data.net

import dev.kodakiko.contract.CollectionCreateRequest
import dev.kodakiko.contract.CollectionDto
import dev.kodakiko.contract.CollectionEntryAddRequest
import dev.kodakiko.contract.CollectionPatchRequest
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.PublicKeyResponse
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.ShareCreateRequest
import dev.kodakiko.contract.ShareListResponse
import kotlinx.serialization.json.JsonElement

object SharingCalls {
    fun create(body: CollectionCreateRequest): ApiCall<CollectionDto> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.collections(),
        body = ContractSchemas.collectionCreateRequest.encode(body),
        responseSchema = ContractSchemas.collectionDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun rename(id: String, metadataBlob: String): ApiCall<CollectionDto> = ApiCall(
        method = HttpKeys.PATCH,
        path = Routes.collection(id),
        body = ContractSchemas.collectionPatchRequest.encode(CollectionPatchRequest(metadataBlob)),
        responseSchema = ContractSchemas.collectionDto,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun remove(id: String): ApiCall<JsonElement?> = ApiCall(
        method = HttpKeys.DELETE,
        path = Routes.collection(id),
        body = null,
        responseSchema = UnknownResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun addEntry(id: String, entryId: String, entryKeyByCollection: String): ApiCall<JsonElement?> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.collectionEntries(id),
        body = ContractSchemas.collectionEntryAddRequest.encode(CollectionEntryAddRequest(entryId, entryKeyByCollection)),
        responseSchema = UnknownResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun removeEntry(id: String, entryId: String): ApiCall<JsonElement?> = ApiCall(
        method = HttpKeys.DELETE,
        path = Routes.collectionEntry(id, entryId),
        body = null,
        responseSchema = UnknownResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun publicKey(username: String): ApiCall<PublicKeyResponse> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.userPublicKey(username),
        body = null,
        responseSchema = ContractSchemas.publicKeyResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun share(id: String, recipientUserId: String, sealedCollectionKey: String): ApiCall<JsonElement?> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.collectionShares(id),
        body = ContractSchemas.shareCreateRequest.encode(ShareCreateRequest(recipientUserId, sealedCollectionKey)),
        responseSchema = UnknownResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun listShares(id: String): ApiCall<ShareListResponse> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.collectionShares(id),
        body = null,
        responseSchema = ContractSchemas.shareListResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun unshare(id: String, recipientUserId: String): ApiCall<JsonElement?> = ApiCall(
        method = HttpKeys.DELETE,
        path = Routes.collectionShare(id, recipientUserId),
        body = null,
        responseSchema = UnknownResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )
}
