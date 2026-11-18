package dev.kodakiko.app.settings

import android.content.Context
import android.content.Intent
import dev.kodakiko.contract.ContractJson
import dev.kodakiko.contract.UserDto
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.files.accountHash
import dev.kodakiko.data.secure.KeyVault
import dev.kodakiko.data.secure.KeyVaultMode
import dev.kodakiko.service.account.AccountService
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AccountStorage
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.account.ServerUrlStore
import dev.kodakiko.service.shareout.ShareLauncher
import dev.kodakiko.service.sharing.SharingService
import dev.kodakiko.ui.feature.settings.AccountFacts
import dev.kodakiko.ui.feature.settings.AdminShare
import dev.kodakiko.ui.feature.settings.KeyStorage
import dev.kodakiko.ui.feature.settings.OpenSourceLicense
import dev.kodakiko.ui.feature.settings.SettingsCredentials
import dev.kodakiko.ui.feature.settings.SettingsLicenses
import dev.kodakiko.ui.feature.settings.SettingsProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun usernameOf(state: AccountState): String = (state as? AccountState.Unlocked)?.username.orEmpty()

class AppSettingsProfile(
    private val accounts: AccountService,
    accountState: Observable<AccountState>,
    private val activeAccountKey: Observable<String?>,
    private val serverUrls: ServerUrlStore,
    private val sharing: SharingService,
    private val rowStore: RowStorePort,
    private val keyVault: KeyVault,
    private val sodium: Sodium,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : SettingsProfile {
    override val username: StateFlow<String> =
        accountState.flow.map(::usernameOf).stateIn(scope, SharingStarted.Eagerly, usernameOf(accountState.value))

    override val unlocked: StateFlow<Boolean> =
        accountState.flow.map { it is AccountState.Unlocked }.stateIn(scope, SharingStarted.Eagerly, accountState.value is AccountState.Unlocked)

    override val user: StateFlow<UserDto?> = accounts.user.flow

    override suspend fun refreshMe(): UserDto? = accounts.refreshMe()

    override suspend fun serverUrl(): String? = serverUrls.current()

    override fun securityCode(): String = sharing.myFingerprint()

    override suspend fun accountFacts(): AccountFacts? = withContext(ioDispatcher) {
        AccountStorage.loadAccount(rowStore)?.let { stored ->
            AccountFacts(
                userId = stored.userId,
                username = stored.username,
                opsLimit = stored.bundle.opsLimit,
                memLimitBytes = stored.bundle.memLimitBytes,
                recoveryConfirmed = stored.recoveryConfirmed,
            )
        }
    }

    override suspend fun keyStorage(): KeyStorage = withContext(ioDispatcher) {
        val accountKey = activeAccountKey.value ?: return@withContext KeyStorage(persistent = false, biometric = false)
        val capabilities = keyVault.capabilities(accountHash(sodium, accountKey))
        KeyStorage(persistent = capabilities.persistent, biometric = capabilities.mode == KeyVaultMode.BIOMETRIC)
    }
}

class AppSettingsCredentials(private val auth: AuthService, private val scope: CoroutineScope) : SettingsCredentials {
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        detached { auth.changePassword(currentPassword, newPassword) }
    }

    override suspend fun revealRecoveryWords(password: String): List<String> = auth.revealRecoveryWords(password)

    override suspend fun verifyPassword(password: String): Boolean = auth.verifyPassword(password)

    override suspend fun requestAccountDeletion() {
        detached { auth.requestAccountDeletion() }
    }

    override suspend fun eraseLocal() {
        detached { auth.eraseLocal() }
    }

    override suspend fun changeServer() {
        detached { auth.changeServer() }
    }
}

class RawLicenses(private val context: Context, private val resource: Int, private val ioDispatcher: CoroutineDispatcher) : SettingsLicenses {
    override suspend fun load(): List<OpenSourceLicense> = withContext(ioDispatcher) {
        val text = context.resources.openRawResource(resource).use { it.readBytes().decodeToString() }
        ContractJson.parse(text).jsonObject.getValue(LicenseKeys.LICENSES).jsonArray.map { element ->
            val item = element.jsonObject
            OpenSourceLicense(
                name = item.getValue(LicenseKeys.NAME).jsonPrimitive.content,
                version = item.getValue(LicenseKeys.VERSION).jsonPrimitive.content,
                license = item.getValue(LicenseKeys.LICENSE).jsonPrimitive.content,
            )
        }
    }
}

class IntentAdminShare(private val launcher: ShareLauncher) : AdminShare {
    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType(SettingsAdapterConstants.TEXT_MIME).putExtra(Intent.EXTRA_TEXT, text)
        launcher.launch(Intent.createChooser(send, null))
    }
}
