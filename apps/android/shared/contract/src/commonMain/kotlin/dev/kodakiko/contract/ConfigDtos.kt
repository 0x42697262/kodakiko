package dev.kodakiko.contract

data class PublicConfigDto(
    val signupMode: SignupMode,
    val sharingEnabled: Boolean,
    val publicLinksEnabled: Boolean,
    val maxMediaPerEntry: Long,
    val maxUploadBytes: Long,
    val defaultQuotaBytes: Long,
    val trashDays: Long,
    val accountDeletionGraceDays: Long,
    val serverVersion: String,
)

internal object ConfigCodecs {
    private val apiVersion: Codec<Unit> = NumberLiteralCodec(API_VERSION)
    private val signupMode: Codec<SignupMode> = enumCodec(SignupMode.entries)

    val publicConfig: Codec<PublicConfigDto> = objectCodec(
        {
            field("apiVersion", apiVersion)
            PublicConfigDto(
                field("signupMode", signupMode),
                field("sharingEnabled", CommonCodecs.boolean),
                field("publicLinksEnabled", CommonCodecs.boolean),
                field("maxMediaPerEntry", CommonCodecs.positive),
                field("maxUploadBytes", CommonCodecs.positive),
                field("defaultQuotaBytes", CommonCodecs.count),
                field("trashDays", CommonCodecs.positive),
                field("accountDeletionGraceDays", CommonCodecs.positive),
                field("serverVersion", CommonCodecs.text),
            )
        },
        {
            field("apiVersion", apiVersion, Unit)
            field("signupMode", signupMode, it.signupMode)
            field("sharingEnabled", CommonCodecs.boolean, it.sharingEnabled)
            field("publicLinksEnabled", CommonCodecs.boolean, it.publicLinksEnabled)
            field("maxMediaPerEntry", CommonCodecs.positive, it.maxMediaPerEntry)
            field("maxUploadBytes", CommonCodecs.positive, it.maxUploadBytes)
            field("defaultQuotaBytes", CommonCodecs.count, it.defaultQuotaBytes)
            field("trashDays", CommonCodecs.positive, it.trashDays)
            field("accountDeletionGraceDays", CommonCodecs.positive, it.accountDeletionGraceDays)
            field("serverVersion", CommonCodecs.text, it.serverVersion)
        },
    )
}
