package dev.kodakiko.service.links

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ContractViolation
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import kotlinx.serialization.json.JsonPrimitive

fun buildLinkUrl(baseUrl: String, linkId: String, secret: ByteArray): String =
    baseUrl.replace(LinksConstants.TRAILING_SLASHES, "") + LinksConstants.LINK_PATH_PREFIX + linkId + LinksConstants.FRAGMENT_MARK + encodeBytes(secret)

fun parseLinkFragment(hash: String): ByteArray? {
    val raw = hash.removePrefix(LinksConstants.FRAGMENT_MARK)
    return try {
        val bytes = decodeBytes(ContractSchemas.bytesSchema.decode(JsonPrimitive(raw)))
        if (bytes.size == LinksConstants.LINK_SECRET_BYTES) bytes else null
    } catch (_: ContractViolation) {
        null
    }
}
