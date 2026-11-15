package dev.kodakiko.account

enum class PasswordRule {
    TOO_SHORT,
    EQUALS_USERNAME,
    SINGLE_REPEATED_CHARACTER,
}

fun checkPasswordPolicy(password: String, normalizedUsername: String): List<PasswordRule> {
    val codePoints = codePointsOf(normalizeNfkc(password))
    val broken = mutableListOf<PasswordRule>()
    if (codePoints.size < PASSWORD_MIN_CODE_POINTS) broken.add(PasswordRule.TOO_SHORT)
    if (normalizeUsername(password) == normalizedUsername) broken.add(PasswordRule.EQUALS_USERNAME)
    if (codePoints.toSet().size == 1) broken.add(PasswordRule.SINGLE_REPEATED_CHARACTER)
    return broken
}
