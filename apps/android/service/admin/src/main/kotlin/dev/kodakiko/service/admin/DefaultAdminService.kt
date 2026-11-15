package dev.kodakiko.service.admin

import dev.kodakiko.contract.AdminInviteCreateRequest
import dev.kodakiko.contract.AdminInviteCreateResponse
import dev.kodakiko.contract.AdminStatsResponse
import dev.kodakiko.contract.AdminUserDto
import dev.kodakiko.contract.AdminUserListResponse
import dev.kodakiko.contract.AdminUserPatchRequest
import dev.kodakiko.contract.InviteListResponse
import dev.kodakiko.data.net.AdminCalls
import dev.kodakiko.data.net.ApiCall
import dev.kodakiko.data.net.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class DefaultAdminService(private val http: HttpClient, private val ioDispatcher: CoroutineDispatcher) : AdminService {
    override suspend fun listUsers(cursor: String?): AdminUserListResponse = send(AdminCalls.listUsers(cursor))

    override suspend fun patchUser(id: String, patch: AdminUserPatchRequest): AdminUserDto = send(AdminCalls.patchUser(id, patch))

    override suspend fun createInvite(input: AdminInviteCreateRequest): AdminInviteCreateResponse = send(AdminCalls.createInvite(input))

    override suspend fun listInvites(): InviteListResponse = send(AdminCalls.listInvites())

    override suspend fun revokeInvite(id: String) {
        send(AdminCalls.revokeInvite(id))
    }

    override suspend fun stats(): AdminStatsResponse = send(AdminCalls.stats())

    private suspend fun <T> send(call: ApiCall<T>): T = withContext(ioDispatcher) { http.request(call) }
}
