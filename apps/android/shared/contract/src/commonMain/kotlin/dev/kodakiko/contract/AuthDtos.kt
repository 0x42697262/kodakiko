package dev.kodakiko.contract

data class ParamsRequest(val username: String)

data class ParamsResponse(val salt: String, val opsLimit: Long, val memLimitBytes: Long)

data class HomeCollectionRequest(val id: String, val keyByOwner: String, val metadataBlob: String)

data class SignUpRequest(
    val userId: String,
    val username: String,
    val inviteToken: String?,
    val deviceLabel: String,
    val salt: String,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val loginKey: String,
    val recoveryLoginKey: String,
    val publicKey: String,
    val masterKeyByKek: String,
    val masterKeyByRecovery: String,
    val recoveryKeyByMaster: String,
    val privateKeyByMaster: String,
    val homeCollection: HomeCollectionRequest,
)

data class SignInRequest(val username: String, val loginKey: String, val deviceLabel: String)

data class RecoveryStartRequest(val username: String)

data class RecoveryStartResponse(val userId: String, val masterKeyByRecovery: String)

data class RecoveryCompleteRequest(
    val username: String,
    val recoveryLoginKey: String,
    val deviceLabel: String,
    val salt: String,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val loginKey: String,
    val masterKeyByKek: String,
)

data class PasswordChangeRequest(
    val currentLoginKey: String,
    val salt: String,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val loginKey: String,
    val masterKeyByKek: String,
)

data class SessionDto(val token: String, val expiresAt: String, val deviceLabel: String)

data class UserDto(
    val id: String,
    val username: String,
    val role: UserRole,
    val status: UserStatus,
    val quotaBytes: Long?,
    val usedBytes: Long,
    val createdAt: String,
    val deletionDueAt: String?,
)

data class KeyBundleDto(
    val salt: String,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val masterKeyByKek: String,
    val recoveryKeyByMaster: String,
    val publicKey: String,
    val privateKeyByMaster: String,
)

data class SignUpResponse(val session: SessionDto, val user: UserDto)

data class SignInResponse(val session: SessionDto, val user: UserDto, val keyBundle: KeyBundleDto)

data class SessionResponse(val user: UserDto)

data class PasswordChangeResponse(val session: SessionDto)

internal object AuthCodecs {
    private val signUpUsername: Codec<String> = StringCodec(pattern = ContractPatterns.USERNAME)
    private val usernameInput: Codec<String> = StringCodec(minLength = 1, maxLength = USERNAME_INPUT_MAX)
    private val deviceLabel: Codec<String> = StringCodec(minLength = 1, maxLength = DEVICE_LABEL_MAX_CHARS)
    private val opsLimit: Codec<Long> = IntegerCodec(minimum = 1, maximum = KDF_OPS_LIMIT_MAX)
    private val memLimitBytes: Codec<Long> = IntegerCodec(minimum = 1)
    private val inviteToken: Codec<String?> = StringCodec(pattern = ContractPatterns.INVITE_TOKEN).nullable()
    private val sessionToken: Codec<String> = StringCodec(pattern = ContractPatterns.SESSION_TOKEN)
    private val id = CommonCodecs.id
    private val bytes = CommonCodecs.bytes
    private val isoTime = CommonCodecs.isoTime

    val paramsRequest: Codec<ParamsRequest> = objectCodec(
        { ParamsRequest(field("username", usernameInput)) },
        { field("username", usernameInput, it.username) },
    )

    val paramsResponse: Codec<ParamsResponse> = objectCodec(
        { ParamsResponse(field("salt", bytes), field("opsLimit", opsLimit), field("memLimitBytes", memLimitBytes)) },
        {
            field("salt", bytes, it.salt)
            field("opsLimit", opsLimit, it.opsLimit)
            field("memLimitBytes", memLimitBytes, it.memLimitBytes)
        },
    )

    private val homeCollection: Codec<HomeCollectionRequest> = objectCodec(
        { HomeCollectionRequest(field("id", id), field("keyByOwner", bytes), field("metadataBlob", bytes)) },
        {
            field("id", id, it.id)
            field("keyByOwner", bytes, it.keyByOwner)
            field("metadataBlob", bytes, it.metadataBlob)
        },
    )

    val signUpRequest: Codec<SignUpRequest> = objectCodec(
        {
            SignUpRequest(
                userId = field("userId", id),
                username = field("username", signUpUsername),
                inviteToken = field("inviteToken", inviteToken),
                deviceLabel = field("deviceLabel", deviceLabel),
                salt = field("salt", bytes),
                opsLimit = field("opsLimit", opsLimit),
                memLimitBytes = field("memLimitBytes", memLimitBytes),
                loginKey = field("loginKey", bytes),
                recoveryLoginKey = field("recoveryLoginKey", bytes),
                publicKey = field("publicKey", bytes),
                masterKeyByKek = field("masterKeyByKek", bytes),
                masterKeyByRecovery = field("masterKeyByRecovery", bytes),
                recoveryKeyByMaster = field("recoveryKeyByMaster", bytes),
                privateKeyByMaster = field("privateKeyByMaster", bytes),
                homeCollection = field("homeCollection", homeCollection),
            )
        },
        {
            field("userId", id, it.userId)
            field("username", signUpUsername, it.username)
            field("inviteToken", inviteToken, it.inviteToken)
            field("deviceLabel", deviceLabel, it.deviceLabel)
            field("salt", bytes, it.salt)
            field("opsLimit", opsLimit, it.opsLimit)
            field("memLimitBytes", memLimitBytes, it.memLimitBytes)
            field("loginKey", bytes, it.loginKey)
            field("recoveryLoginKey", bytes, it.recoveryLoginKey)
            field("publicKey", bytes, it.publicKey)
            field("masterKeyByKek", bytes, it.masterKeyByKek)
            field("masterKeyByRecovery", bytes, it.masterKeyByRecovery)
            field("recoveryKeyByMaster", bytes, it.recoveryKeyByMaster)
            field("privateKeyByMaster", bytes, it.privateKeyByMaster)
            field("homeCollection", homeCollection, it.homeCollection)
        },
    )

    val signInRequest: Codec<SignInRequest> = objectCodec(
        { SignInRequest(field("username", usernameInput), field("loginKey", bytes), field("deviceLabel", deviceLabel)) },
        {
            field("username", usernameInput, it.username)
            field("loginKey", bytes, it.loginKey)
            field("deviceLabel", deviceLabel, it.deviceLabel)
        },
    )

    val recoveryStartRequest: Codec<RecoveryStartRequest> = objectCodec(
        { RecoveryStartRequest(field("username", usernameInput)) },
        { field("username", usernameInput, it.username) },
    )

    val recoveryStartResponse: Codec<RecoveryStartResponse> = objectCodec(
        { RecoveryStartResponse(field("userId", id), field("masterKeyByRecovery", bytes)) },
        {
            field("userId", id, it.userId)
            field("masterKeyByRecovery", bytes, it.masterKeyByRecovery)
        },
    )

    val recoveryCompleteRequest: Codec<RecoveryCompleteRequest> = objectCodec(
        {
            RecoveryCompleteRequest(
                username = field("username", usernameInput),
                recoveryLoginKey = field("recoveryLoginKey", bytes),
                deviceLabel = field("deviceLabel", deviceLabel),
                salt = field("salt", bytes),
                opsLimit = field("opsLimit", opsLimit),
                memLimitBytes = field("memLimitBytes", memLimitBytes),
                loginKey = field("loginKey", bytes),
                masterKeyByKek = field("masterKeyByKek", bytes),
            )
        },
        {
            field("username", usernameInput, it.username)
            field("recoveryLoginKey", bytes, it.recoveryLoginKey)
            field("deviceLabel", deviceLabel, it.deviceLabel)
            field("salt", bytes, it.salt)
            field("opsLimit", opsLimit, it.opsLimit)
            field("memLimitBytes", memLimitBytes, it.memLimitBytes)
            field("loginKey", bytes, it.loginKey)
            field("masterKeyByKek", bytes, it.masterKeyByKek)
        },
    )

    val passwordChangeRequest: Codec<PasswordChangeRequest> = objectCodec(
        {
            PasswordChangeRequest(
                currentLoginKey = field("currentLoginKey", bytes),
                salt = field("salt", bytes),
                opsLimit = field("opsLimit", opsLimit),
                memLimitBytes = field("memLimitBytes", memLimitBytes),
                loginKey = field("loginKey", bytes),
                masterKeyByKek = field("masterKeyByKek", bytes),
            )
        },
        {
            field("currentLoginKey", bytes, it.currentLoginKey)
            field("salt", bytes, it.salt)
            field("opsLimit", opsLimit, it.opsLimit)
            field("memLimitBytes", memLimitBytes, it.memLimitBytes)
            field("loginKey", bytes, it.loginKey)
            field("masterKeyByKek", bytes, it.masterKeyByKek)
        },
    )

    val sessionDto: Codec<SessionDto> = objectCodec(
        { SessionDto(field("token", sessionToken), field("expiresAt", isoTime), field("deviceLabel", deviceLabel)) },
        {
            field("token", sessionToken, it.token)
            field("expiresAt", isoTime, it.expiresAt)
            field("deviceLabel", deviceLabel, it.deviceLabel)
        },
    )

    private val quotaBytes: Codec<Long?> = CommonCodecs.count.nullable()
    private val nullableIsoTime: Codec<String?> = isoTime.nullable()

    internal fun ObjectReader.readUser(): UserDto =
        UserDto(
            id = field("id", id),
            username = field("username", CommonCodecs.text),
            role = field("role", CommonCodecs.userRole),
            status = field("status", CommonCodecs.userStatus),
            quotaBytes = field("quotaBytes", quotaBytes),
            usedBytes = field("usedBytes", CommonCodecs.count),
            createdAt = field("createdAt", isoTime),
            deletionDueAt = field("deletionDueAt", nullableIsoTime),
        )

    internal fun ObjectWriter.writeUser(user: UserDto) {
        field("id", id, user.id)
        field("username", CommonCodecs.text, user.username)
        field("role", CommonCodecs.userRole, user.role)
        field("status", CommonCodecs.userStatus, user.status)
        field("quotaBytes", quotaBytes, user.quotaBytes)
        field("usedBytes", CommonCodecs.count, user.usedBytes)
        field("createdAt", isoTime, user.createdAt)
        field("deletionDueAt", nullableIsoTime, user.deletionDueAt)
    }

    val userDto: Codec<UserDto> = objectCodec({ readUser() }, { writeUser(it) })

    val keyBundleDto: Codec<KeyBundleDto> = objectCodec(
        {
            KeyBundleDto(
                salt = field("salt", bytes),
                opsLimit = field("opsLimit", opsLimit),
                memLimitBytes = field("memLimitBytes", memLimitBytes),
                masterKeyByKek = field("masterKeyByKek", bytes),
                recoveryKeyByMaster = field("recoveryKeyByMaster", bytes),
                publicKey = field("publicKey", bytes),
                privateKeyByMaster = field("privateKeyByMaster", bytes),
            )
        },
        {
            field("salt", bytes, it.salt)
            field("opsLimit", opsLimit, it.opsLimit)
            field("memLimitBytes", memLimitBytes, it.memLimitBytes)
            field("masterKeyByKek", bytes, it.masterKeyByKek)
            field("recoveryKeyByMaster", bytes, it.recoveryKeyByMaster)
            field("publicKey", bytes, it.publicKey)
            field("privateKeyByMaster", bytes, it.privateKeyByMaster)
        },
    )

    val signUpResponse: Codec<SignUpResponse> = objectCodec(
        { SignUpResponse(field("session", sessionDto), field("user", userDto)) },
        {
            field("session", sessionDto, it.session)
            field("user", userDto, it.user)
        },
    )

    val signInResponse: Codec<SignInResponse> = objectCodec(
        { SignInResponse(field("session", sessionDto), field("user", userDto), field("keyBundle", keyBundleDto)) },
        {
            field("session", sessionDto, it.session)
            field("user", userDto, it.user)
            field("keyBundle", keyBundleDto, it.keyBundle)
        },
    )

    val sessionResponse: Codec<SessionResponse> = objectCodec(
        { SessionResponse(field("user", userDto)) },
        { field("user", userDto, it.user) },
    )

    val passwordChangeResponse: Codec<PasswordChangeResponse> = objectCodec(
        { PasswordChangeResponse(field("session", sessionDto)) },
        { field("session", sessionDto, it.session) },
    )
}
