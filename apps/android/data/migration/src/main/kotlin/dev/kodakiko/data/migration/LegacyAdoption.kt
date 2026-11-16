package dev.kodakiko.data.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.isDecodableBase64Url
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.CryptoException
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.openBlob
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.NewerSchemaError
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.files.accountHash
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.LegacyRead
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

interface LegacySecrets {
    fun read(name: String): LegacyRead

    fun forgetAll()
}

interface AdoptionTarget {
    fun saveServerUrl(url: String)

    fun saveActiveAccount(account: ActiveAccount)

    fun saveServerConfigJson(json: String)

    fun saveSession(accountHash: String, token: String)

    fun saveMasterKey(accountHash: String, masterKey: ByteArray): Boolean
}

enum class AdoptionStatus { ADOPTED, ALREADY_ADOPTED, FRESH_INSTALL, NEWER_DATABASE, DATABASE_MISSING, CORRUPT_DATABASE }

data class AdoptionResult(val status: AdoptionStatus, val accountHash: String?, val masterKeyCarried: Boolean, val needsPassword: Boolean)

class LegacyAdoption(
    private val context: Context,
    private val sodium: Sodium,
    private val legacy: LegacySecrets,
    private val target: AdoptionTarget,
    private val clock: () -> Instant = { Instant.now() },
) {
    fun pendingAccount(): ActiveAccount? = (legacy.read(LegacyKeys.ACTIVE_ACCOUNT) as? LegacyRead.Value)?.text?.let(ActiveAccount::parse)

    fun run(): AdoptionResult {
        val account = pendingAccount()
            ?: return AdoptionResult(AdoptionStatus.FRESH_INSTALL, null, masterKeyCarried = false, needsPassword = false)
        val hash = accountHash(sodium, account.accountKey)
        val file = KodakikoDatabase.databaseFile(context, hash)
        if (!file.isFile) return stopped(AdoptionStatus.DATABASE_MISSING, hash)
        if (!passesIntegrityCheck(file.path)) return stopped(AdoptionStatus.CORRUPT_DATABASE, hash)
        val database = try {
            KodakikoDatabase.open(context, hash)
        } catch (failure: NewerSchemaError) {
            return stopped(AdoptionStatus.NEWER_DATABASE, hash)
        }
        val carried = database.use { adopt(it, account, hash) }
        legacy.forgetAll()
        return if (carried == null) {
            stopped(AdoptionStatus.ALREADY_ADOPTED, hash)
        } else {
            AdoptionResult(AdoptionStatus.ADOPTED, hash, masterKeyCarried = carried, needsPassword = !carried)
        }
    }

    private fun passesIntegrityCheck(path: String): Boolean =
        try {
            SQLiteDatabase.openDatabase(path, null, READ_ONLY_FLAGS) { }.use { database ->
                database.rawQuery(MigrationConstants.INTEGRITY_CHECK, null).use { cursor ->
                    cursor.count == 1 && cursor.moveToFirst() && cursor.getString(0) == MigrationConstants.INTEGRITY_OK
                }
            }
        } catch (failure: SQLiteException) {
            false
        }

    private fun stopped(status: AdoptionStatus, hash: String) = AdoptionResult(status, hash, masterKeyCarried = false, needsPassword = false)

    private fun adopt(database: KodakikoDatabase, account: ActiveAccount, hash: String): Boolean? {
        if (database.kvGet(StorageKeys.MIGRATION_V031_ADOPTED) != null) return null
        valueOf(LegacyKeys.SERVER)?.let(target::saveServerUrl)
        target.saveActiveAccount(account)
        valueOf(LegacyKeys.SERVER_CONFIG)?.let(target::saveServerConfigJson)
        valueOf(LegacyKeys.SESSION_PREFIX + hash)?.let { target.saveSession(hash, it) }
        val carried = carryMasterKey(database, hash)
        database.kvSetJson(
            StorageKeys.MIGRATION_V031_ADOPTED,
            buildJsonObject {
                put(MigrationConstants.MARKER_SCHEMA_VERSION_FIELD, MigrationConstants.MARKER_SCHEMA_VERSION)
                put(MigrationConstants.MARKER_AT_FIELD, ISO_TIME.format(clock()))
            },
        )
        return carried
    }

    private fun valueOf(name: String): String? = (legacy.read(name) as? LegacyRead.Value)?.text

    private fun carryMasterKey(database: KodakikoDatabase, hash: String): Boolean {
        val masterKey = valueOf(LegacyKeys.MASTER_KEY_PREFIX + hash)?.let(::decodeMasterKey) ?: return false
        try {
            return opensJournal(database, masterKey) && target.saveMasterKey(hash, masterKey)
        } finally {
            wipe(masterKey)
        }
    }

    private fun decodeMasterKey(text: String): ByteArray? {
        if (!isDecodableBase64Url(text)) return null
        val bytes = decodeBytes(text)
        if (bytes.size == KEY_BYTES) return bytes
        wipe(bytes)
        return null
    }

    private fun opensJournal(database: KodakikoDatabase, masterKey: ByteArray): Boolean {
        val userId = (database.kvGetJson(StorageKeys.ACCOUNT_USER_ID) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return JOURNAL_WRAPS.any { (key, context) ->
            val wrapped = database.kvGet(key) ?: return@any false
            try {
                wipe(openBlob(sodium, masterKey, wrapped, context, userId))
                true
            } catch (failure: CryptoException) {
                false
            }
        }
    }

    private companion object {
        const val READ_ONLY_FLAGS = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        val ISO_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern(MigrationConstants.ISO_TIME_PATTERN).withZone(ZoneOffset.UTC)
        val JOURNAL_WRAPS = listOf(
            StorageKeys.ACCOUNT_PRIVATE_KEY_BY_MASTER to AdContext.PRIVATE_KEY_BY_MASTER,
            StorageKeys.ACCOUNT_RECOVERY_KEY_BY_MASTER to AdContext.RECOVERY_KEY_BY_MASTER,
        )
    }
}
