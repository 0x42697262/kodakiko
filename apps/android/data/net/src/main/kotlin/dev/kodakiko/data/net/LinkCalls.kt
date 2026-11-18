package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.contract.PublicLinkCreateRequest
import dev.kodakiko.contract.PublicLinkDto
import dev.kodakiko.contract.PublicLinkListResponse
import dev.kodakiko.contract.Routes
import kotlinx.serialization.json.JsonElement

object LinkCalls {
    fun create(collectionId: String, accessToken: String, expiry: LinkExpiry): ApiCall<PublicLinkDto> = ApiCall(
        method = HttpKeys.POST,
        path = Routes.publicLinks(),
        body = ContractSchemas.publicLinkCreateRequest.encode(PublicLinkCreateRequest(collectionId, accessToken, expiry)),
        responseSchema = ContractSchemas.publicLinkDto,
        auth = ApiAuth.Session,
        idempotent = false,
    )

    fun list(): ApiCall<PublicLinkListResponse> = ApiCall(
        method = HttpKeys.GET,
        path = Routes.publicLinks(),
        body = null,
        responseSchema = ContractSchemas.publicLinkListResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )

    fun revoke(linkId: String): ApiCall<JsonElement?> = ApiCall(
        method = HttpKeys.DELETE,
        path = Routes.publicLink(linkId),
        body = null,
        responseSchema = UnknownResponse,
        auth = ApiAuth.Session,
        idempotent = true,
    )
}
