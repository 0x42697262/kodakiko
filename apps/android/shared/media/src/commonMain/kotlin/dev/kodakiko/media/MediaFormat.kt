package dev.kodakiko.media

import dev.kodakiko.contract.MediaKind

data class MediaFormat(val kind: MediaKind, val mimeType: String, val container: MediaContainer)

private fun brandsOf(header: ByteArray): List<String> {
    val brands = mutableListOf(readAscii(header, FTYP_MAJOR_BRAND_OFFSET.toLong(), FTYP_BRAND_BYTES.toLong()))
    val boxSize = readUint32(header, 0)
    val end = minOf(boxSize, header.size.toLong())
    var offset = FTYP_FIRST_COMPATIBLE_OFFSET.toLong()
    while (offset + FTYP_BRAND_BYTES <= end) {
        brands.add(readAscii(header, offset, FTYP_BRAND_BYTES.toLong()))
        offset += FTYP_BRAND_BYTES
    }
    return brands
}

private fun sniffFtyp(header: ByteArray): MediaFormat? {
    if (header.size < FTYP_MIN_BYTES) return null
    if (readAscii(header, FTYP_TAG_OFFSET.toLong(), FTYP_TAG.length.toLong()) != FTYP_TAG) return null
    val brands = brandsOf(header)
    val major = brands.first()
    if (brands.any { it in HEIF_BRANDS }) {
        val heic = brands.any { it in HEIC_BRANDS }
        return MediaFormat(MediaKind.PHOTO, if (heic) MIME_HEIC else MIME_HEIF, MediaContainer.HEIF)
    }
    if (major in MOV_BRANDS) return MediaFormat(MediaKind.VIDEO, MIME_MOV, MediaContainer.MOV)
    if (major in MP4_BRANDS) return MediaFormat(MediaKind.VIDEO, MIME_MP4, MediaContainer.MP4)
    return null
}

fun sniffFormat(header: ByteArray): MediaFormat? {
    val window = subarray(header, 0, SNIFF_HEADER_BYTES.toLong())
    for (signature in SIGNATURES) {
        if (signature.parts.all { startsWithBytes(window, it.offset.toLong(), it.bytes) }) {
            return MediaFormat(signature.kind, signature.mimeType, signature.container)
        }
    }
    return sniffFtyp(window)
}
