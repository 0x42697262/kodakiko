package dev.kodakiko.contract

internal fun canParseUrl(input: String): Boolean {
    val cleaned = input.trim { it <= JSON_CONTROL_CHAR_LIMIT }.filterNot { it in ContractPatterns.URL_TAB_AND_NEWLINE }
    val scheme = ContractPatterns.URL_SCHEME.find(cleaned) ?: return false
    val name = scheme.groupValues[1].lowercase()
    val rest = cleaned.substring(scheme.range.last + 1)
    return when {
        name == ContractPatterns.URL_FILE_SCHEME -> fileUrlValid(rest)
        name in ContractPatterns.URL_SPECIAL_SCHEMES -> specialUrlValid(rest)
        rest.startsWith(ContractPatterns.URL_AUTHORITY_SLASHES) -> nonSpecialAuthorityValid(rest.drop(ContractPatterns.URL_AUTHORITY_SLASHES.length))
        else -> true
    }
}

private fun hostOfAuthority(authority: String): String? {
    val host = authority.substringAfterLast(ContractPatterns.URL_CREDENTIALS_END)
    return if (host.isEmpty() && authority.contains(ContractPatterns.URL_CREDENTIALS_END)) null else host
}

private fun authorityOf(text: String, ends: Set<Char>): String {
    val end = text.indexOfFirst { it in ends }
    return if (end < 0) text else text.substring(0, end)
}

private fun specialUrlValid(rest: String): Boolean {
    val authority = authorityOf(rest.trimStart { it in ContractPatterns.URL_SLASHES }, ContractPatterns.URL_AUTHORITY_END)
    return hostAndPortValid(hostOfAuthority(authority) ?: return false, special = true)
}

private fun nonSpecialAuthorityValid(rest: String): Boolean {
    val authority = authorityOf(rest, ContractPatterns.URL_NON_SPECIAL_AUTHORITY_END)
    return hostAndPortValid(hostOfAuthority(authority) ?: return false, special = false)
}

private fun fileUrlValid(rest: String): Boolean {
    val slashes = rest.takeWhile { it in ContractPatterns.URL_SLASHES }
    if (slashes.length < ContractPatterns.URL_AUTHORITY_SLASHES.length) return true
    val host = authorityOf(rest.drop(ContractPatterns.URL_AUTHORITY_SLASHES.length), ContractPatterns.URL_AUTHORITY_END)
    if (host.isEmpty()) return true
    return if (host.startsWith(ContractPatterns.URL_IPV6_START)) bracketedHostValid(host) else domainValid(host)
}

private fun hostAndPortValid(hostAndPort: String, special: Boolean): Boolean {
    val host: String
    val port: String?
    if (hostAndPort.startsWith(ContractPatterns.URL_IPV6_START)) {
        val close = hostAndPort.indexOf(ContractPatterns.URL_IPV6_END)
        if (close < 0) return false
        host = hostAndPort.substring(0, close + 1)
        val remainder = hostAndPort.substring(close + 1)
        if (remainder.isNotEmpty() && remainder.first() != ContractPatterns.URL_PORT_SEPARATOR) return false
        port = if (remainder.isEmpty()) null else remainder.drop(1)
    } else {
        host = hostAndPort.substringBefore(ContractPatterns.URL_PORT_SEPARATOR)
        port = if (hostAndPort.contains(ContractPatterns.URL_PORT_SEPARATOR)) hostAndPort.substringAfter(ContractPatterns.URL_PORT_SEPARATOR) else null
    }
    if (port != null && !portValid(port)) return false
    if (host.isEmpty()) return !special && port == null
    return when {
        host.startsWith(ContractPatterns.URL_IPV6_START) -> bracketedHostValid(host)
        special -> domainValid(host)
        else -> host.none { it in ContractPatterns.URL_FORBIDDEN_HOST }
    }
}

private fun portValid(port: String): Boolean {
    if (port.any { !it.isAsciiDigit() }) return false
    val significant = port.trimStart(ContractPatterns.ZERO)
    return significant.length <= URL_PORT_DIGITS_MAX && (significant.toIntOrNull() ?: 0) <= URL_PORT_MAX
}

private fun Char.isAsciiDigit(): Boolean = this in ContractPatterns.ZERO..JS_DIGIT_NINE

private fun bracketedHostValid(host: String): Boolean =
    host.endsWith(ContractPatterns.URL_IPV6_END) && ipv6Valid(host.substring(1, host.length - 1))

private fun percentDecode(text: String): String? {
    val bytes = ArrayList<Byte>()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val hex = if (char == ContractPatterns.URL_PERCENT && index + URL_PERCENT_ENCODED_LENGTH <= text.length) {
            text.substring(index + 1, index + URL_PERCENT_ENCODED_LENGTH).toIntOrNull(HEX_RADIX)
        } else {
            null
        }
        if (hex != null && text[index + 1] != ContractPatterns.PLUS && text[index + 1] != ContractPatterns.MINUS) {
            bytes += hex.toByte()
            index += URL_PERCENT_ENCODED_LENGTH
        } else {
            val end = if (char.isHighSurrogate() && index + 1 < text.length) index + SURROGATE_PAIR_LENGTH else index + 1
            bytes.addAll(text.substring(index, end).encodeToByteArray().toList())
            index = end
        }
    }
    return try {
        bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (error: CharacterCodingException) {
        null
    }
}

private fun domainValid(host: String): Boolean {
    val decoded = percentDecode(host)?.map(::idnaMap)?.joinToString("")?.lowercase() ?: return false
    if (decoded.any { it in IDNA_JOINERS || it in IDNA_C1_CONTROL_MIN..IDNA_C1_CONTROL_MAX }) return false
    val domain = decoded.filterNot { it in IDNA_IGNORED }
    if (domain.isEmpty()) return false
    if (domain.split(ContractPatterns.URL_LABEL_SEPARATOR).any { !aceLabelValid(it) }) return false
    val forbidden = domain.any {
        it <= URL_C0_CONTROL_MAX || it == URL_DELETE || it == ContractPatterns.URL_PERCENT || it in ContractPatterns.URL_FORBIDDEN_HOST
    }
    if (forbidden) return false
    return !endsInNumber(domain) || ipv4Valid(domain)
}

private fun labelsOf(domain: String): List<String> {
    val labels = domain.split(ContractPatterns.URL_LABEL_SEPARATOR)
    return if (labels.size > 1 && labels.last().isEmpty()) labels.dropLast(1) else labels
}

private fun endsInNumber(domain: String): Boolean {
    val last = labelsOf(domain).last()
    if (last.isEmpty()) return false
    return ContractPatterns.ASCII_DIGITS.matches(last) || ipv4Number(last) != null
}

private fun ipv4Number(part: String): Long? {
    if (part.isEmpty()) return null
    var digits = part
    var radix = DECIMAL_RADIX
    if (digits.length >= RADIX_PREFIX_LENGTH && digits.take(RADIX_PREFIX_LENGTH).lowercase() == ContractPatterns.HEX_PREFIX) {
        digits = digits.drop(RADIX_PREFIX_LENGTH)
        radix = HEX_RADIX
    } else if (digits.length > 1 && digits.first() == ContractPatterns.ZERO) {
        digits = digits.drop(1)
        radix = OCTAL_RADIX
    }
    var value = 0L
    for (char in digits) {
        val digit = char.digitToIntOrNull(radix) ?: return null
        value = minOf(value * radix + digit, IPV4_VALUE_CAP)
    }
    return value
}

private fun ipv4Valid(domain: String): Boolean {
    val parts = labelsOf(domain)
    if (parts.size > IPV4_PARTS_MAX) return false
    val numbers = parts.map { ipv4Number(it) ?: return false }
    if (numbers.dropLast(1).any { it > IPV4_PART_MAX }) return false
    var limit = 1L
    repeat(IPV4_PARTS_MAX + 1 - numbers.size) { limit *= IPV4_PART_BASE }
    return numbers.last() < limit
}

private class Ipv6Cursor(val text: String) {
    var index = 0

    fun current(): Char? = text.getOrNull(index)
}

private fun ipv6Valid(input: String): Boolean {
    val cursor = Ipv6Cursor(input)
    var pieceIndex = 0
    var compressed = false
    if (cursor.current() == ContractPatterns.URL_PORT_SEPARATOR) {
        if (input.getOrNull(1) != ContractPatterns.URL_PORT_SEPARATOR) return false
        cursor.index += IPV6_COMPRESSION_LENGTH
        pieceIndex += 1
        compressed = true
    }
    while (cursor.current() != null) {
        if (pieceIndex == IPV6_PIECES) return false
        if (cursor.current() == ContractPatterns.URL_PORT_SEPARATOR) {
            if (compressed) return false
            cursor.index += 1
            pieceIndex += 1
            compressed = true
            continue
        }
        var length = 0
        while (length < IPV6_PIECE_HEX_DIGITS && cursor.current()?.digitToIntOrNull(HEX_RADIX) != null) {
            cursor.index += 1
            length += 1
        }
        when (cursor.current()) {
            ContractPatterns.DECIMAL_POINT -> {
                if (length == 0 || pieceIndex > IPV6_LAST_IPV4_PIECE) return false
                cursor.index -= length
                return embeddedIpv4Valid(cursor, pieceIndex, compressed)
            }
            ContractPatterns.URL_PORT_SEPARATOR -> {
                cursor.index += 1
                if (cursor.current() == null) return false
            }
            null -> Unit
            else -> return false
        }
        pieceIndex += 1
    }
    return compressed || pieceIndex == IPV6_PIECES
}

private fun embeddedIpv4Valid(cursor: Ipv6Cursor, startPiece: Int, compressed: Boolean): Boolean {
    var pieceIndex = startPiece
    var numbersSeen = 0
    while (cursor.current() != null) {
        if (numbersSeen > 0) {
            if (cursor.current() != ContractPatterns.DECIMAL_POINT || numbersSeen >= IPV6_IPV4_PARTS) return false
            cursor.index += 1
        }
        if (cursor.current()?.isAsciiDigit() != true) return false
        var value: Long? = null
        while (true) {
            val digit = cursor.current()?.takeIf { it.isAsciiDigit() }?.digitToInt()?.toLong() ?: break
            value = when (value) {
                null -> digit
                0L -> return false
                else -> value * DECIMAL_RADIX + digit
            }
            if (value > IPV4_PART_MAX) return false
            cursor.index += 1
        }
        numbersSeen += 1
        if (numbersSeen % IPV6_IPV4_PARTS_PER_PIECE == 0) pieceIndex += 1
    }
    if (numbersSeen != IPV6_IPV4_PARTS) return false
    return compressed || pieceIndex == IPV6_PIECES
}

private fun idnaMap(char: Char): Char =
    when (char) {
        in IDNA_FULL_STOPS -> ContractPatterns.URL_LABEL_SEPARATOR
        in IDNA_FULLWIDTH_MIN..IDNA_FULLWIDTH_MAX -> char - IDNA_FULLWIDTH_OFFSET
        else -> char
    }

private fun aceLabelValid(label: String): Boolean {
    if (!label.startsWith(ContractPatterns.IDNA_ACE_PREFIX)) return true
    val decoded = punycodeDecode(label.drop(ContractPatterns.IDNA_ACE_PREFIX.length)) ?: return false
    return decoded.any { it >= IDNA_C1_CONTROL_MIN.code } &&
        decoded.none { it in IDNA_C1_CONTROL_MIN.code..IDNA_C1_CONTROL_MAX.code || it < JSON_CONTROL_CHAR_LIMIT.code }
}

private fun punycodeDigit(char: Char): Int? =
    when (char) {
        in 'a'..'z' -> char - 'a'
        in '0'..'9' -> char - '0' + PUNYCODE_DIGIT_LETTERS
        else -> null
    }

private fun punycodeAdapt(delta: Long, points: Int, first: Boolean): Long {
    var scaled = if (first) delta / PUNYCODE_DAMP else delta / PUNYCODE_HALF
    scaled += scaled / points
    var k = 0L
    val threshold = ((PUNYCODE_BASE - PUNYCODE_T_MIN) * PUNYCODE_T_MAX) / PUNYCODE_HALF
    while (scaled > threshold) {
        scaled /= PUNYCODE_BASE - PUNYCODE_T_MIN
        k += PUNYCODE_BASE
    }
    return k + (PUNYCODE_BASE - PUNYCODE_T_MIN + 1) * scaled / (scaled + PUNYCODE_SKEW)
}

private fun punycodeDecode(input: String): List<Int>? {
    val delimiter = input.lastIndexOf(ContractPatterns.PUNYCODE_DELIMITER)
    val output = ArrayList<Int>()
    if (delimiter > 0) {
        if (input.substring(0, delimiter).any { it.code >= PUNYCODE_INITIAL_N }) return null
        input.substring(0, delimiter).forEach { output += it.code }
    }
    var index = if (delimiter > 0) delimiter + 1 else 0
    var n = PUNYCODE_INITIAL_N.toLong()
    var bias = PUNYCODE_INITIAL_BIAS.toLong()
    var i = 0L
    while (index < input.length) {
        val oldI = i
        var weight = 1L
        var k = PUNYCODE_BASE.toLong()
        while (true) {
            val digit = input.getOrNull(index)?.let { punycodeDigit(it) } ?: return null
            index += 1
            i += digit * weight
            if (i > UNICODE_MAX_CODE_POINT.toLong() * PUNYCODE_INITIAL_N) return null
            val threshold = (k - bias).coerceIn(PUNYCODE_T_MIN.toLong(), PUNYCODE_T_MAX.toLong())
            if (digit < threshold) break
            weight *= PUNYCODE_BASE - threshold
            k += PUNYCODE_BASE
        }
        val points = output.size + 1
        bias = punycodeAdapt(i - oldI, points, oldI == 0L)
        n += i / points
        i %= points
        if (n > UNICODE_MAX_CODE_POINT) return null
        output.add(i.toInt(), n.toInt())
        i += 1
    }
    return output
}
