@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val TS_CONFIG = PublicConfigDto(
    signupMode = SignupMode.INVITE,
    sharingEnabled = true,
    publicLinksEnabled = false,
    maxMediaPerEntry = 20,
    maxUploadBytes = 3_758_096_384L,
    defaultQuotaBytes = 107_374_182_400L,
    trashDays = 30,
    accountDeletionGraceDays = 7,
    serverVersion = "0.2.0",
)

private val TS_ACCOUNT = AccountFacts(userId = "USER1", username = "ada", opsLimit = 16, memLimitBytes = 268_435_456, recoveryConfirmed = true)

private val TS_INPUT = ServerFactsInput(
    serverUrl = "https://kodakiko.example.com",
    config = TS_CONFIG,
    user = ServerUsage(usedBytes = 1_073_741_824L, quotaBytes = null),
    account = TS_ACCOUNT,
    fingerprint = "1234 5678",
    keyStorage = KeyStorage(persistent = true, biometric = true),
    encryptThumbnails = false,
    local = false,
)

private val TS_LOCAL = ServerFactsInput(
    serverUrl = null,
    config = null,
    user = null,
    account = TS_ACCOUNT.copy(recoveryConfirmed = false),
    fingerprint = "",
    keyStorage = KeyStorage(persistent = true, biometric = false),
    encryptThumbnails = true,
    local = true,
)

@RunWith(RobolectricTestRunner::class)
class ServerFactSectionsTest {
    private fun rows(input: ServerFactsInput): Map<String, String> =
        serverFactSections(resources, input).flatMap { section -> section.rows.map { it.label to it.value } }.toMap()

    @Test
    fun describesTheServerItsFeaturesAndItsLimits() {
        val rows = rows(TS_INPUT)
        assertEquals("https://kodakiko.example.com", rows["Address"])
        assertEquals("0.2.0 (API 1)", rows["Server version"])
        assertEquals("Invite only", rows["Sign-up"])
        assertEquals("On", rows["Sharing"])
        assertEquals("Off", rows["Public links"])
        assertEquals("3.5 GB", rows["Largest upload"])
        assertEquals("1 GB of 100 GB", rows["Storage used"])
    }

    @Test
    fun describesTheAccountKeysAndHowTheyAreStored() {
        val rows = rows(TS_INPUT)
        assertEquals("USER1", rows["Account id"])
        assertEquals("1234 5678", rows["Security code"])
        assertEquals("Argon2id, 16 passes, 256 MB", rows["Password hashing"])
        assertEquals("Confirmed", rows["Recovery key"])
        assertEquals("Kept, unlock with fingerprint or PIN", rows["Key on this device"])
        assertEquals("XChaCha20-Poly1305, 1 MB chunks", rows["Media encryption"])
    }

    @Test
    fun showsOnlyTheLocalFactsInLocalMode() {
        val titles = serverFactSections(resources, TS_LOCAL).map { it.title }
        assertFalse(titles.contains("Server"))
    }

    @Test
    fun theSectionsAndRowsFollowTheV031Order() {
        val sections = serverFactSections(resources, TS_INPUT)
        assertEquals(listOf(text(R.string.server_section), text(R.string.server_account), text(R.string.server_keys)), sections.map { it.title })
        assertEquals(
            listOf(
                R.string.server_address,
                R.string.server_version,
                R.string.server_signup,
                R.string.server_sharing,
                R.string.server_links,
                R.string.server_max_media,
                R.string.server_max_upload,
                R.string.server_trash_days,
                R.string.server_used,
            ).map { text(it) },
            sections[0].rows.map { it.label },
        )
        assertEquals("20", sections[0].rows[5].value)
        assertEquals(text(R.string.server_days, 30), sections[0].rows[7].value)
        assertEquals(
            listOf(R.string.server_account_id, R.string.server_username, R.string.server_code, R.string.server_hashing, R.string.server_recovery).map { text(it) },
            sections[1].rows.map { it.label },
        )
        assertEquals("ada", sections[1].rows[1].value)
        assertEquals(
            listOf(R.string.server_key_storage, R.string.server_media, R.string.server_metadata, R.string.server_sharing_keys, R.string.server_thumbnails).map { text(it) },
            sections[2].rows.map { it.label },
        )
        assertEquals(
            listOf(
                text(R.string.server_key_biometric),
                text(R.string.server_media_value, "1 MB"),
                text(R.string.server_metadata_value),
                text(R.string.server_sharing_keys_value),
                text(R.string.server_thumbnails_plain),
            ),
            sections[2].rows.map { it.value },
        )
    }

    @Test
    fun theUserQuotaWinsOverTheDefaultAndAnUnknownUserHasNoUsageRow() {
        assertEquals("1 GB of 2 GB", rows(TS_INPUT.copy(user = ServerUsage(1_073_741_824L, 2_147_483_648L)))["Storage used"])
        assertNull(rows(TS_INPUT.copy(user = null))["Storage used"])
    }

    @Test
    fun anOpenServerAndOtherKeyStoragesUseTheirV031Texts() {
        val open = rows(TS_INPUT.copy(config = TS_CONFIG.copy(signupMode = SignupMode.OPEN, publicLinksEnabled = true)))
        assertEquals(text(R.string.server_signup_open), open["Sign-up"])
        assertEquals(text(R.string.server_on), open["Public links"])
        assertEquals(text(R.string.server_key_kept), rows(TS_INPUT.copy(keyStorage = KeyStorage(true, false)))["Key on this device"])
        assertEquals(text(R.string.server_key_not_kept), rows(TS_INPUT.copy(keyStorage = KeyStorage(false, true)))["Key on this device"])
    }

    @Test
    fun aLocalJournalHasNoSecurityCodeRowAndShowsItsOwnRecoveryAndThumbnailState() {
        val sections = serverFactSections(resources, TS_LOCAL)
        assertEquals(listOf(text(R.string.server_account), text(R.string.server_keys)), sections.map { it.title })
        val rows = rows(TS_LOCAL)
        assertNull(rows[text(R.string.server_code)])
        assertEquals(text(R.string.server_recovery_not_confirmed), rows[text(R.string.server_recovery)])
        assertEquals(text(R.string.server_thumbnails_encrypted), rows[text(R.string.server_thumbnails)])
        assertEquals(text(R.string.server_key_kept), rows[text(R.string.server_key_storage)])
    }

    @Test
    fun aServerAccountWithoutKnownConfigHasNoServerSection() {
        assertEquals(listOf(text(R.string.server_account), text(R.string.server_keys)), serverFactSections(resources, TS_INPUT.copy(config = null)).map { it.title })
    }
}

@RunWith(RobolectricTestRunner::class)
class ServerKeysViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private fun TestScope.open(): ServerKeysViewModel {
        val vm = ServerKeysViewModel(h.accountDeps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun aServerAccountGathersTheServerTheUserTheStoredKeysAndTheDeviceStorage() = runTest {
        val vm = open()
        assertEquals(
            ServerFactsInput(
                serverUrl = SERVER_URL,
                config = publicConfig(),
                user = ServerUsage(usedBytes = 340 * 1024L, quotaBytes = null),
                account = AccountFacts(USER_ID, USERNAME, 16, 268_435_456, true),
                fingerprint = SECURITY_CODE,
                keyStorage = KeyStorage(persistent = true, biometric = true),
                encryptThumbnails = false,
                local = false,
            ),
            vm.state.value.facts,
        )
    }

    @Test
    fun aLocalJournalHasNoServerAddressAndNoSecurityCode() = runTest {
        h.account.modeState.value = AccountMode.LOCAL
        h.profile.userState.value = null
        val facts = open().state.value.facts!!
        assertNull(facts.serverUrl)
        assertEquals("", facts.fingerprint)
        assertTrue(facts.local)
        assertNull(facts.user)
        assertFalse(h.profile.calls.contains(FakeSettingsProfile.SECURITY_CODE_CALL))
        assertFalse(h.profile.calls.contains(FakeSettingsProfile.SERVER_URL_CALL))
    }

    @Test
    fun anUnreadableKeyStoreCountsAsNotKept() = runTest {
        h.profile.storageFailure = IllegalStateException("keystore")
        assertEquals(KeyStorage(persistent = false, biometric = false), open().state.value.facts?.keyStorage)
    }

    @Test
    fun withoutAStoredAccountThereIsNothingToShow() = runTest {
        h.profile.facts = null
        assertNull(open().state.value.facts)
    }

    @Test
    fun theThumbnailSettingAndTheUserAreFollowedLive() = runTest {
        val vm = open()
        h.store.update { it.copy(encryptThumbnails = true) }
        runCurrent()
        assertEquals(true, vm.state.value.facts?.encryptThumbnails)
        h.profile.userState.value = userDto(quotaBytes = 2 * GB, usedBytes = GB)
        runCurrent()
        assertEquals(ServerUsage(GB, 2 * GB), vm.state.value.facts?.user)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2000dp-mdpi")
class ServerKeysScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()

    @Test
    fun theScreenListsTheSectionsWithTheirRows() {
        val navigator = RecordingNavigator(listOf(Route.SettingsAccount, Route.SettingsServer))
        compose.showWith(navigator) { ServerKeysScreen(viewModel = ServerKeysViewModel(h.accountDeps)) }
        compose.waitUntil(WAIT_MS) { compose.exists(TestTags.Server.SCREEN) && compose.showsText(USER_ID) }
        assertTrue(compose.showsText(text(R.string.server_title)))
        for (title in listOf(R.string.server_section, R.string.server_account, R.string.server_keys)) assertTrue(compose.showsText(text(title)))
        assertTrue(compose.showsText(text(R.string.server_address)))
        assertTrue(compose.showsText(SERVER_URL))
        assertTrue(compose.showsText(SECURITY_CODE))
        assertTrue(compose.showsText(text(R.string.server_key_biometric)))
        compose.onNodeWithTag(TestTags.Settings.BACK).performClick()
        compose.waitForIdle()
        assertEquals(1, navigator.pops)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
