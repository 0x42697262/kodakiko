package dev.kodakiko.contract

data class AdminUserDto(val user: UserDto, val sessionCount: Long, val lastActiveAt: String?)

data class AdminUserListResponse(val items: List<AdminUserDto>, val nextCursor: String?)

data class AdminUserPatchRequest(
    val quotaBytes: OptionalValue<Long?> = OptionalValue.Absent,
    val role: UserRole? = null,
)

data class AdminInviteCreateRequest(val role: UserRole, val expiresInDays: Long)

data class AdminInviteCreateResponse(val inviteUrl: String, val expiresAt: String)

data class InviteDto(
    val id: String,
    val role: UserRole,
    val createdAt: String,
    val expiresAt: String,
    val redeemedAt: String?,
    val revokedAt: String?,
    val redeemedByUsername: String?,
)

data class InviteListResponse(val items: List<InviteDto>)

data class AdminStatsResponse(
    val users: Long,
    val activeUsers30d: Long,
    val pendingDeletion: Long,
    val collections: Long,
    val entries: Long,
    val objects: Long,
    val totalBytes: Long,
    val quotaBytesAllocated: Long?,
)

internal object AdminCodecs {
    private val id = CommonCodecs.id
    private val isoTime = CommonCodecs.isoTime
    private val count = CommonCodecs.count
    private val nullableIsoTime: Codec<String?> = isoTime.nullable()
    private val nullableCount: Codec<Long?> = count.nullable()
    private val nullableText: Codec<String?> = CommonCodecs.text.nullable()

    val adminUserDto: Codec<AdminUserDto> = objectCodec(
        {
            val user = with(AuthCodecs) { readUser() }
            AdminUserDto(user, field("sessionCount", count), field("lastActiveAt", nullableIsoTime))
        },
        {
            with(AuthCodecs) { writeUser(it.user) }
            field("sessionCount", count, it.sessionCount)
            field("lastActiveAt", nullableIsoTime, it.lastActiveAt)
        },
    )

    private val adminUsers: Codec<List<AdminUserDto>> = adminUserDto.array()

    val adminUserListResponse: Codec<AdminUserListResponse> = objectCodec(
        { AdminUserListResponse(field("items", adminUsers), field("nextCursor", nullableText)) },
        {
            field("items", adminUsers, it.items)
            field("nextCursor", nullableText, it.nextCursor)
        },
    )

    private val patchQuota: Codec<OptionalValue<Long?>> = nullableCount.presence()
    private val patchRole: Codec<UserRole?> = CommonCodecs.userRole.optional()

    val adminUserPatchRequest: Codec<AdminUserPatchRequest> = objectCodec<AdminUserPatchRequest>(
        { AdminUserPatchRequest(field("quotaBytes", patchQuota), field("role", patchRole)) },
        {
            field("quotaBytes", patchQuota, it.quotaBytes)
            field("role", patchRole, it.role)
        },
    ).refine(ContractText.PATCH_EMPTY) { it.quotaBytes != OptionalValue.Absent || it.role != null }

    private val expiresInDays: Codec<Long> = IntegerCodec(minimum = 1, maximum = INVITE_MAX_EXPIRY_DAYS)

    val adminInviteCreateRequest: Codec<AdminInviteCreateRequest> = objectCodec(
        { AdminInviteCreateRequest(field("role", CommonCodecs.userRole), field("expiresInDays", expiresInDays)) },
        {
            field("role", CommonCodecs.userRole, it.role)
            field("expiresInDays", expiresInDays, it.expiresInDays)
        },
    )

    val adminInviteCreateResponse: Codec<AdminInviteCreateResponse> = objectCodec(
        { AdminInviteCreateResponse(field("inviteUrl", CommonCodecs.text), field("expiresAt", isoTime)) },
        {
            field("inviteUrl", CommonCodecs.text, it.inviteUrl)
            field("expiresAt", isoTime, it.expiresAt)
        },
    )

    val inviteDto: Codec<InviteDto> = objectCodec(
        {
            InviteDto(
                id = field("id", id),
                role = field("role", CommonCodecs.userRole),
                createdAt = field("createdAt", isoTime),
                expiresAt = field("expiresAt", isoTime),
                redeemedAt = field("redeemedAt", nullableIsoTime),
                revokedAt = field("revokedAt", nullableIsoTime),
                redeemedByUsername = field("redeemedByUsername", nullableText),
            )
        },
        {
            field("id", id, it.id)
            field("role", CommonCodecs.userRole, it.role)
            field("createdAt", isoTime, it.createdAt)
            field("expiresAt", isoTime, it.expiresAt)
            field("redeemedAt", nullableIsoTime, it.redeemedAt)
            field("revokedAt", nullableIsoTime, it.revokedAt)
            field("redeemedByUsername", nullableText, it.redeemedByUsername)
        },
    )

    private val invites: Codec<List<InviteDto>> = inviteDto.array()

    val inviteListResponse: Codec<InviteListResponse> = objectCodec(
        { InviteListResponse(field("items", invites)) },
        { field("items", invites, it.items) },
    )

    val adminStatsResponse: Codec<AdminStatsResponse> = objectCodec(
        {
            AdminStatsResponse(
                users = field("users", count),
                activeUsers30d = field("activeUsers30d", count),
                pendingDeletion = field("pendingDeletion", count),
                collections = field("collections", count),
                entries = field("entries", count),
                objects = field("objects", count),
                totalBytes = field("totalBytes", count),
                quotaBytesAllocated = field("quotaBytesAllocated", nullableCount),
            )
        },
        {
            field("users", count, it.users)
            field("activeUsers30d", count, it.activeUsers30d)
            field("pendingDeletion", count, it.pendingDeletion)
            field("collections", count, it.collections)
            field("entries", count, it.entries)
            field("objects", count, it.objects)
            field("totalBytes", count, it.totalBytes)
            field("quotaBytesAllocated", nullableCount, it.quotaBytesAllocated)
        },
    )
}
