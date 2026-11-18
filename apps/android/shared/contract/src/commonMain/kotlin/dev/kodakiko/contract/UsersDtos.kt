package dev.kodakiko.contract

data class DeletionRequestResponse(val deletionDueAt: String)

data class UserPublicKeyParams(val username: String)

data class PublicKeyResponse(val userId: String, val username: String, val publicKey: String)

internal object UsersCodecs {
    val deletionRequestResponse: Codec<DeletionRequestResponse> = objectCodec(
        { DeletionRequestResponse(field("deletionDueAt", CommonCodecs.isoTime)) },
        { field("deletionDueAt", CommonCodecs.isoTime, it.deletionDueAt) },
    )

    val userPublicKeyParams: Codec<UserPublicKeyParams> = objectCodec(
        { UserPublicKeyParams(field("username", CommonCodecs.nonEmptyText)) },
        { field("username", CommonCodecs.nonEmptyText, it.username) },
    )

    val publicKeyResponse: Codec<PublicKeyResponse> = objectCodec(
        { PublicKeyResponse(field("userId", CommonCodecs.id), field("username", CommonCodecs.text), field("publicKey", CommonCodecs.bytes)) },
        {
            field("userId", CommonCodecs.id, it.userId)
            field("username", CommonCodecs.text, it.username)
            field("publicKey", CommonCodecs.bytes, it.publicKey)
        },
    )
}
