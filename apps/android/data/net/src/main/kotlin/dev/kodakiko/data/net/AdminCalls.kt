package dev.kodakiko.data.net

import dev.kodakiko.contract.AdminInviteCreateRequest
import dev.kodakiko.contract.AdminInviteCreateResponse
import dev.kodakiko.contract.AdminStatsResponse
import dev.kodakiko.contract.AdminUserDto
import dev.kodakiko.contract.AdminUserListResponse
import dev.kodakiko.contract.AdminUserPatchRequest
import dev.kodakiko.contract.ContractSchema
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.InviteListResponse
import dev.kodakiko.contract.Routes
import kotlinx.serialization.json.JsonElement

object AdminCalls {
    fun listUsers(cursor: String?): ApiCall<AdminUserListResponse> = call(
        HttpKeys.GET,
        Routes.adminUsers() + if (cursor == null) "" else HttpKeys.QUERY_START + HttpKeys.CURSOR_PARAMETER + encodeUriComponent(cursor),
        ContractSchemas.adminUserListResponse,
    )

    fun patchUser(id: String, patch: AdminUserPatchRequest): ApiCall<AdminUserDto> =
        call(HttpKeys.PATCH, Routes.adminUser(id), ContractSchemas.adminUserDto, ContractSchemas.adminUserPatchRequest.encode(patch))

    fun createInvite(input: AdminInviteCreateRequest): ApiCall<AdminInviteCreateResponse> =
        call(HttpKeys.POST, Routes.adminInvites(), ContractSchemas.adminInviteCreateResponse, ContractSchemas.adminInviteCreateRequest.encode(input))

    fun listInvites(): ApiCall<InviteListResponse> = call(HttpKeys.GET, Routes.adminInvites(), ContractSchemas.inviteListResponse)

    fun revokeInvite(id: String): ApiCall<JsonElement?> = call(HttpKeys.DELETE, Routes.adminInvite(id), UnknownResponse)

    fun stats(): ApiCall<AdminStatsResponse> = call(HttpKeys.GET, Routes.adminStats(), ContractSchemas.adminStatsResponse)

    private fun <T> call(method: String, path: String, schema: ContractSchema<T>, body: JsonElement? = null): ApiCall<T> =
        ApiCall(method, path, body, schema, ApiAuth.Session, idempotent = method != HttpKeys.POST)
}
