package dev.kodakiko.service.admin

import dev.kodakiko.contract.AdminInviteCreateRequest
import dev.kodakiko.contract.AdminInviteCreateResponse
import dev.kodakiko.contract.AdminStatsResponse
import dev.kodakiko.contract.AdminUserDto
import dev.kodakiko.contract.AdminUserListResponse
import dev.kodakiko.contract.AdminUserPatchRequest
import dev.kodakiko.contract.InviteListResponse

interface AdminService {
    suspend fun listUsers(cursor: String?): AdminUserListResponse

    suspend fun patchUser(id: String, patch: AdminUserPatchRequest): AdminUserDto

    suspend fun createInvite(input: AdminInviteCreateRequest): AdminInviteCreateResponse

    suspend fun listInvites(): InviteListResponse

    suspend fun revokeInvite(id: String)

    suspend fun stats(): AdminStatsResponse
}
