@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.lock

import android.content.Context
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyTaskType
import dev.kodakiko.account.KdfParams
import dev.kodakiko.account.deriveLoginMaterial
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.migration.AdoptionTarget
import dev.kodakiko.data.migration.LegacyAdoption
import dev.kodakiko.data.migration.LegacySecrets
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.DeviceLock
import dev.kodakiko.data.secure.LegacyRead
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.ServerChoice
import dev.kodakiko.service.account.ServerChoiceFailure
import dev.kodakiko.service.lock.AutoLock
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.service.lock.LockControlDeps
import dev.kodakiko.service.lock.LockReason
import dev.kodakiko.service.lock.LockService
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.security.MessageDigest

const val FIXTURE_HASH = "b1abbb91a5439ec1"
const val FIXTURE_ACCOUNT_KEY = "local/AJodCY5u6JCoGljpGC5X5w"
const val FIXTURE_USERNAME = "k007local"
const val GOOD_PASSWORD = "correct horse battery staple"

val context: Context get() = ApplicationProvider.getApplicationContext()

val resources: Resources get() = context.resources

class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class Observed<S, E>(private val state: StateFlow<S>, val events: List<E>) {
    val value: S get() = state.value
}

fun <S, E> TestScope.observe(state: StateFlow<S>, events: Flow<E>): Observed<S, E> {
    val received = mutableListOf<E>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { events.collect { received += it } }
    return Observed(state, received)
}

data class Call(val name: String, val args: List<Any?>)

class FakeAuthService : AuthService {
    override val mode: Observable<AccountMode> = Observable(AccountMode.SERVER)

    val calls = mutableListOf<Call>()
    val failures = mutableMapOf<String, Throwable>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    var deviceUnlocks = false
    var username: String? = "alice"

    fun count(name: String): Int = calls.count { it.name == name }

    fun args(name: String): List<Any?> = calls.last { it.name == name }.args

    fun hold(name: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[name] = it }

    private suspend fun <T> record(name: String, vararg args: Any?, result: () -> T): T {
        calls += Call(name, args.toList())
        gates[name]?.await()
        failures[name]?.let { throw it }
        return result()
    }

    override suspend fun restore() = record("restore") {}

    override suspend fun chooseServer(input: String): ServerChoice = record("chooseServer", input) { ServerChoice.Failed(ServerChoiceFailure.UNREACHABLE) }

    override suspend fun signUp(username: String, password: String, inviteToken: String?): List<String> = record("signUp") { emptyList() }

    override suspend fun confirmRecoveryKeySaved() = record("confirmRecoveryKeySaved") {}

    override suspend fun signIn(username: String, password: String) = record("signIn") {}

    override suspend fun unlockWithPassword(password: String) = record("unlockWithPassword", password) {}

    override suspend fun unlockWithDevice(): Boolean = record("unlockWithDevice") { deviceUnlocks }

    override suspend fun recoverAccount(username: String, words: String, newPassword: String) = record("recoverAccount") {}

    override suspend fun pendingRecoveryWords(): List<String>? = record("pendingRecoveryWords") { null }

    override suspend fun recoveryPending(): Boolean = record("recoveryPending") { false }

    override suspend fun cancelDeletion() = record("cancelDeletion") {}

    override suspend fun signOut() = record("signOut") {}

    override suspend fun activeUsername(): String? = record("activeUsername") { username }

    override suspend fun changePassword(currentPassword: String, newPassword: String) = record("changePassword") {}

    override suspend fun revealRecoveryWords(password: String): List<String> = record("revealRecoveryWords") { emptyList() }

    override suspend fun verifyPassword(password: String): Boolean = record("verifyPassword") { true }

    override suspend fun requestAccountDeletion(): String = record("requestAccountDeletion") { "" }

    override fun deletionScheduledFor(): String? = null

    override suspend fun changeServer() = record("changeServer") {}

    override suspend fun setUpLocal(username: String, password: String): List<String> = record("setUpLocal") { emptyList() }

    override suspend fun connectServer(serverInput: String, inviteToken: String?, username: String, password: String) = record("connectServer") {}

    override suspend fun eraseLocal() = record("eraseLocal") {}
}

class FakeLockService(var locks: Int = 0) : LockService {
    override suspend fun lock(reason: LockReason) {
        locks++
    }

    override suspend fun afterUnlock() = Unit

    override fun isLocking(): Boolean = false

    override fun bind(): () -> Unit = {}

    override suspend fun idle() = Unit

    override fun lockCount(): Int = locks
}

class FakeAutoLock : AutoLock {
    override fun start() = Unit

    override fun stop() = Unit

    override fun pause() = Unit

    override fun resume() = Unit
}

fun lockControl(locksInSession: Int, deviceLock: DeviceLock = DeviceLock.BIOMETRIC): LockControl =
    LockControl(
        LockControlDeps(
            lockService = FakeLockService(locksInSession),
            autoLock = FakeAutoLock(),
            accountState = Observable<AccountState>(AccountState.Locked),
            deviceLock = { deviceLock },
        ),
    )

class FakeLegacy(val items: MutableMap<String, LegacyRead> = mutableMapOf()) : LegacySecrets {
    var forgotten = 0
    val reads = mutableListOf<String>()

    override fun read(name: String): LegacyRead {
        reads += name
        return items[name] ?: LegacyRead.Missing
    }

    override fun forgetAll() {
        forgotten++
        items.clear()
    }
}

class FakeTarget : AdoptionTarget {
    var calls = 0
    var account: ActiveAccount? = null
    val masterKeys = mutableMapOf<String, ByteArray>()

    override fun saveServerUrl(url: String) {
        calls++
    }

    override fun saveActiveAccount(account: ActiveAccount) {
        calls++
        this.account = account
    }

    override fun saveServerConfigJson(json: String) {
        calls++
    }

    override fun saveSession(accountHash: String, token: String) {
        calls++
    }

    override fun saveMasterKey(accountHash: String, masterKey: ByteArray): Boolean {
        calls++
        masterKeys[accountHash] = masterKey.copyOf()
        return true
    }
}

object Fixture {
    val sodium: Sodium by lazy { createSodium() }

    private val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
    }

    private val local: File get() = File(repoRoot, "tests/fixtures/v031-device/local")

    val activeAccountJson: String =
        "{\"schemaVersion\":2,\"accountKey\":\"$FIXTURE_ACCOUNT_KEY\",\"username\":\"$FIXTURE_USERNAME\",\"mode\":\"local\"}"

    val databaseFile: File get() = KodakikoDatabase.databaseFile(context, FIXTURE_HASH)

    fun install() {
        val sqlite = File(context.filesDir, "SQLite").apply { mkdirs() }
        for (suffix in listOf("", "-wal", "-shm")) {
            File(local, "files/SQLite/kodakiko-$FIXTURE_HASH.db$suffix").copyTo(File(sqlite, "kodakiko-$FIXTURE_HASH.db$suffix"), overwrite = true)
        }
        File(local, "files/kodakiko").copyRecursively(File(context.filesDir, "kodakiko"), overwrite = true)
    }

    fun corrupt() {
        install()
        KodakikoDatabase.open(context, FIXTURE_HASH).close()
        for (suffix in listOf("-wal", "-shm", "-journal")) File(databaseFile.path + suffix).delete()
        val bytes = databaseFile.readBytes()
        val declared = ((bytes[16].toInt() and 0xFF) shl 8) or (bytes[17].toInt() and 0xFF)
        val pageSize = if (declared == 1) 65_536 else declared
        check(bytes.size >= pageSize * 2) { "fixture database has a single page" }
        for (index in pageSize until bytes.size) bytes[index] = 0x5A
        databaseFile.writeBytes(bytes)
    }

    fun legacy(masterKey: ByteArray? = null): FakeLegacy =
        FakeLegacy(
            buildMap {
                put("kodakiko.activeAccount", LegacyRead.Value(activeAccountJson))
                if (masterKey != null) put("kodakiko.mk.$FIXTURE_HASH", LegacyRead.Value(encodeBytes(masterKey)))
                if (masterKey != null) put("kodakiko.mkauth.$FIXTURE_HASH", LegacyRead.Value("biometric"))
            }.toMutableMap(),
        )

    private var cachedMasterKey: ByteArray? = null

    fun masterKey(): ByteArray {
        cachedMasterKey?.let { return it.copyOf() }
        val password = Json.parseToJsonElement(File(local, "account.json").readText()).jsonObject.getValue("PASSWORD").jsonPrimitive.content
        val database = KodakikoDatabase.open(context, FIXTURE_HASH)
        try {
            val kdf = database.kvGetJson(StorageKeys.ACCOUNT_KDF_PARAMS)!!.jsonObject
            val params = KdfParams(decodeBytes(kdf.getValue("salt").jsonPrimitive.content), kdf.getValue("opsLimit").jsonPrimitive.long, kdf.getValue("memLimitBytes").jsonPrimitive.long)
            val login = deriveLoginMaterial(sodium, password, params)
            val userId = database.kvGetJson(StorageKeys.ACCOUNT_USER_ID)!!.jsonPrimitive.content
            val masterKey = unwrapKey(sodium, login.kek, database.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK)!!, AdContext.MASTER_KEY_BY_KEK, userId)
            wipe(login.kek)
            wipe(login.loginKey)
            cachedMasterKey = masterKey.copyOf()
            return masterKey
        } finally {
            database.close()
        }
    }

    fun adoption(legacy: LegacySecrets, target: AdoptionTarget = FakeTarget()): LegacyAdoption = LegacyAdoption(context, sodium, legacy, target)

    fun emptyAdoption(): LegacyAdoption = adoption(FakeLegacy())

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

fun AndroidComposeTestRule<*, ComponentActivity>.show(appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
    setContent { KodakikoTheme(appTheme = appTheme) { content() } }
    waitForIdle()
}

fun AndroidComposeTestRule<*, *>.exists(tag: String): Boolean = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

val isPassword: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

object Screenshots {
    const val QUALIFIERS = "w360dp-h640dp-xhdpi"

    private const val DIRECTORY = "src/test/screenshots"

    fun path(name: String): String = "$DIRECTORY/$name.png"

    fun options(name: String): RoborazziOptions {
        val requested = roborazziSystemPropertyTaskType()
        val taskType = when {
            requested.isEnabled() -> requested
            File(path(name)).isFile -> RoborazziTaskType.Verify
            else -> RoborazziTaskType.Record
        }
        return RoborazziOptions(taskType = taskType)
    }
}

fun SemanticsNodeInteraction.captureScreenshot(name: String) {
    captureRoboImage(Screenshots.path(name), Screenshots.options(name))
}
