package dev.kodakiko.ui.feature.auth

import dev.kodakiko.account.PasswordRule
import dev.kodakiko.account.checkPasswordPolicy
import dev.kodakiko.account.normalizeUsername
import dev.kodakiko.crypto.RandomSource
import dev.kodakiko.js.jsTrim
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

fun extractInviteToken(input: String): String? {
    val trimmed = jsTrim(input)
    if (AuthConstants.INVITE_TOKEN.matches(trimmed)) return trimmed
    val uri = try {
        URI(trimmed)
    } catch (failure: URISyntaxException) {
        return null
    }
    if (uri.scheme == null) return null
    val token = uri.rawFragment ?: return null
    return token.takeIf { AuthConstants.INVITE_TOKEN.matches(it) }
}

fun pickConfirmPositions(random: RandomSource, total: Int, count: Int): List<Int> {
    val limit = AuthConstants.RANDOM_INDEX_LIMIT - AuthConstants.RANDOM_INDEX_LIMIT % total
    val chosen = linkedSetOf<Int>()
    while (chosen.size < count) {
        val value = random.next(AuthConstants.RANDOM_INDEX_BYTES).firstOrNull()?.toInt()?.and(AuthConstants.BYTE_MASK) ?: limit
        if (value < limit) chosen += value % total + 1
    }
    return chosen.sorted()
}

fun passwordHints(password: String, username: String, touched: Boolean): List<PasswordRule> =
    if (touched) checkPasswordPolicy(password, normalizeUsername(username)) else emptyList()

fun formatDeletionDate(dueAt: String, zone: ZoneId): String =
    try {
        AuthConstants.DELETION_DATE.format(Instant.parse(dueAt).atZone(zone))
    } catch (failure: DateTimeParseException) {
        ""
    }
