package dev.kodakiko.ui.feature.auth

import dev.kodakiko.account.PasswordRule
import dev.kodakiko.crypto.RandomSource
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.format.errorBanner
import dev.kodakiko.ui.core.format.passwordRuleTag
import dev.kodakiko.ui.core.format.passwordRuleText
import dev.kodakiko.ui.core.format.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthHelpersTest {
    private val wordTotal = 24
    private val confirmCount = 3

    private fun streamOf(values: List<Int>, cycle: Boolean): RandomSource {
        var next = 0
        return RandomSource { length ->
            assertEquals(1, length)
            val value = if (cycle) values[next++ % values.size] else values.getOrElse(next++) { 0 }
            byteArrayOf(value.toByte())
        }
    }

    @Test
    fun acceptsAnInviteLinkABareTokenAndRejectsAnythingElse() {
        assertEquals(TOKEN, extractInviteToken("https://journal.example.test/invite#$TOKEN"))
        assertEquals(TOKEN, extractInviteToken("  $TOKEN  "))
        assertNull(extractInviteToken("https://journal.example.test/invite"))
        assertNull(extractInviteToken("not a link"))
    }

    @Test
    fun inviteInputRejectsATokenOfTheWrongShapeEvenInsideALink() {
        assertNull(extractInviteToken("https://journal.example.test/invite#kdk_i_short"))
        assertNull(extractInviteToken("kdk_i_short"))
        assertNull(extractInviteToken(""))
        assertEquals(TOKEN, extractInviteToken("\n$INVITE_LINK\t"))
    }

    @Test
    fun returnsThreeDistinctAscendingOneBasedPositionsInsideTheWordCount() {
        val positions = pickConfirmPositions(streamOf(listOf(200, 5, 5, 30, 255, 47, 12, 100), cycle = true), wordTotal, confirmCount)
        assertEquals(confirmCount, positions.size)
        assertEquals(confirmCount, positions.toSet().size)
        assertEquals(positions.sorted(), positions)
        for (position in positions) {
            assertTrue(position >= 1)
            assertTrue(position <= wordTotal)
        }
    }

    @Test
    fun rejectsRandomValuesThatWouldBiasTheResult() {
        assertEquals(listOf(1, 2, 3), pickConfirmPositions(streamOf(listOf(255, 254, 0, 1, 2), cycle = false), wordTotal, confirmCount))
    }

    @Test
    fun keepsTheFirstUnbiasedDrawsInAscendingOrder() {
        assertEquals(listOf(6, 9, 18), pickConfirmPositions(streamOf(listOf(17, 240, 5, 8, 32), cycle = false), wordTotal, confirmCount))
    }

    @Test
    fun showsNoHintBeforeTheFieldWasTouchedAndEachFailingRuleAfter() {
        assertEquals(emptyList<PasswordRule>(), passwordHints("short", "alice", false))
        assertEquals(listOf(PasswordRule.TOO_SHORT), passwordHints("short", "alice", true))
        assertEquals(listOf(PasswordRule.SINGLE_REPEATED_CHARACTER), passwordHints("aaaaaaaaaaaa", "alice", true))
    }

    @Test
    fun passwordHintsCompareWithTheNormalizedUsername() {
        assertEquals(listOf(PasswordRule.EQUALS_USERNAME), passwordHints("alice.example", "  Alice.Example ", true))
        assertEquals(emptyList<PasswordRule>(), passwordHints(GOOD_PASSWORD, "alice", true))
    }

    @Test
    fun givesTheTwoCredentialFailuresOneTextAndTheWordPositionItsOwnText() {
        assertEquals(
            errorBanner(AuthError(AuthFailure.WRONG_PASSWORD)).text(resources),
            errorBanner(AuthError(AuthFailure.INVALID_CREDENTIALS)).text(resources),
        )
        assertEquals("Word 5 isn't in the recovery word list.", errorBanner(AuthError(AuthFailure.RECOVERY_WORDS_INVALID, position = 5)).text(resources))
        assertEquals(TestTags.Errors.RECOVERY_WORDS_INVALID, errorBanner(AuthError(AuthFailure.RECOVERY_WORDS_INVALID)).testTag)
    }

    @Test
    fun everyFailureHasTheV031TagAndText() {
        val expected = mapOf(
            AuthFailure.WRONG_PASSWORD to (TestTags.Errors.WRONG_PASSWORD to "Username or password is incorrect."),
            AuthFailure.INVALID_CREDENTIALS to (TestTags.Errors.INVALID_CREDENTIALS to "Username or password is incorrect."),
            AuthFailure.UNREACHABLE to (TestTags.Errors.UNREACHABLE to "Can't reach the server. Check your connection or VPN, then try again."),
            AuthFailure.NOT_HTTPS to (TestTags.Errors.NOT_HTTPS to "The server address must start with https://."),
            AuthFailure.NOT_KODAKIKO to (TestTags.Errors.NOT_KODAKIKO to "That address isn't a Kodakiko server."),
            AuthFailure.INVITE_INVALID to (TestTags.Errors.INVITE_INVALID to "This invite link is invalid, used or expired. Ask for a new one."),
            AuthFailure.USERNAME_TAKEN to (TestTags.Errors.USERNAME_TAKEN to "That username is taken."),
            AuthFailure.USERNAME_INVALID to (TestTags.Errors.USERNAME_INVALID to "Use 3–32 lowercase letters, digits, dots, dashes or underscores."),
            AuthFailure.UNSAFE_SERVER to (
                TestTags.Errors.UNSAFE_SERVER to "The server asked for unsafe security settings, so sign-in stopped. Contact the server's owner."
                ),
            AuthFailure.KDF_UNAVAILABLE to (TestTags.Errors.KDF_UNAVAILABLE to "This device doesn't have enough memory to secure the account."),
            AuthFailure.RATE_LIMITED to (TestTags.Errors.RATE_LIMITED to "Too many attempts. Wait a minute and try again."),
            AuthFailure.GENERIC to (TestTags.Errors.GENERIC to "Something went wrong. Try again."),
            AuthFailure.RECOVERY_WORDS_INVALID to (TestTags.Errors.RECOVERY_WORDS_INVALID to "These words don't form a valid recovery key."),
        )
        assertEquals(AuthFailure.entries.toSet() - AuthFailure.PASSWORD_POLICY, expected.keys)
        for ((reason, banner) in expected) {
            val actual = errorBanner(AuthError(reason))
            assertEquals("$reason", banner.first, actual.testTag)
            assertEquals("$reason", banner.second, actual.text(resources))
        }
    }

    @Test
    fun aPasswordPolicyFailureShowsItsFirstRuleAndFallsBackToTheGenericText() {
        val first = errorBanner(AuthError(AuthFailure.PASSWORD_POLICY, rules = listOf(PasswordRule.EQUALS_USERNAME, PasswordRule.TOO_SHORT)))
        assertEquals(TestTags.Password.EQUALS_USERNAME, first.testTag)
        assertEquals("Don't use your username as your password.", first.text(resources))
        val none = errorBanner(AuthError(AuthFailure.PASSWORD_POLICY))
        assertEquals(TestTags.Errors.GENERIC, none.testTag)
        assertEquals("Something went wrong. Try again.", none.text(resources))
    }

    @Test
    fun passwordRulesHaveTheV031TagsAndTexts() {
        assertEquals(TestTags.Password.TOO_SHORT, passwordRuleTag(PasswordRule.TOO_SHORT))
        assertEquals(TestTags.Password.EQUALS_USERNAME, passwordRuleTag(PasswordRule.EQUALS_USERNAME))
        assertEquals(TestTags.Password.SINGLE_REPEATED_CHARACTER, passwordRuleTag(PasswordRule.SINGLE_REPEATED_CHARACTER))
        assertEquals("Use at least 12 characters.", resources.getString(passwordRuleText(PasswordRule.TOO_SHORT)))
        assertEquals("Don't use your username as your password.", resources.getString(passwordRuleText(PasswordRule.EQUALS_USERNAME)))
        assertEquals("Don't use one character repeated.", resources.getString(passwordRuleText(PasswordRule.SINGLE_REPEATED_CHARACTER)))
    }
}
