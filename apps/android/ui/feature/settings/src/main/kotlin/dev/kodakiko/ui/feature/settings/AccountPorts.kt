package dev.kodakiko.ui.feature.settings

import androidx.compose.runtime.Immutable
import dev.kodakiko.contract.UserDto
import dev.kodakiko.service.admin.AdminService
import dev.kodakiko.service.diagnostics.DiagnosticsService
import dev.kodakiko.service.index.IndexService
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class AccountFacts(val userId: String, val username: String, val opsLimit: Long, val memLimitBytes: Long, val recoveryConfirmed: Boolean)

@Immutable
data class KeyStorage(val persistent: Boolean, val biometric: Boolean)

interface SettingsProfile {
    val username: StateFlow<String>

    val unlocked: StateFlow<Boolean>

    val user: StateFlow<UserDto?>

    suspend fun refreshMe(): UserDto?

    suspend fun serverUrl(): String?

    fun securityCode(): String

    suspend fun accountFacts(): AccountFacts?

    suspend fun keyStorage(): KeyStorage
}

interface SettingsCredentials {
    suspend fun changePassword(currentPassword: String, newPassword: String)

    suspend fun revealRecoveryWords(password: String): List<String>

    suspend fun verifyPassword(password: String): Boolean

    suspend fun requestAccountDeletion()

    suspend fun eraseLocal()

    suspend fun changeServer()
}

class AccountSettingsDeps(
    val settings: SettingsDeps,
    val profile: SettingsProfile,
    val credentials: SettingsCredentials,
    val nowMs: () -> Long,
)

class AdvancedSettingsDeps(
    val settings: SettingsDeps,
    val index: IndexService,
    val diagnostics: DiagnosticsService,
    val showDiagnostics: Boolean,
)

@Immutable
data class OpenSourceLicense(val name: String, val version: String, val license: String)

fun interface SettingsLicenses {
    suspend fun load(): List<OpenSourceLicense>
}

class AboutDeps(
    val settings: SettingsDeps,
    val appVersion: String,
    val licenses: SettingsLicenses,
)

fun interface AdminShare {
    fun share(text: String)
}

class AdminDeps(
    val settings: SettingsDeps,
    val admin: AdminService,
    val share: AdminShare,
    val nowMs: () -> Long,
)
