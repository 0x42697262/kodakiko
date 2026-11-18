package dev.kodakiko.contract

import kotlinx.serialization.json.JsonElement

data class ErrorBody(val code: ErrorCode, val message: String, val requestId: String, val details: JsonElement)

data class ErrorResponse(val error: ErrorBody)

internal object ErrorCodecs {
    private val code: Codec<ErrorCode> = enumCodec(ErrorCode.entries)

    private val body: Codec<ErrorBody> = objectCodec(
        {
            ErrorBody(
                field("code", code),
                field("message", CommonCodecs.text),
                field("requestId", CommonCodecs.text),
                field("details", CommonCodecs.unknown),
            )
        },
        {
            field("code", code, it.code)
            field("message", CommonCodecs.text, it.message)
            field("requestId", CommonCodecs.text, it.requestId)
            field("details", CommonCodecs.unknown, it.details)
        },
    )

    val errorResponse: Codec<ErrorResponse> = objectCodec(
        { ErrorResponse(field("error", body)) },
        { field("error", body, it.error) },
    )
}
