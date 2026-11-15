package dev.kodakiko.service.account

import dev.kodakiko.account.KeyBundleBytes
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.contract.isValidId
import dev.kodakiko.data.db.StorageKeys
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

class StoredAccount(
    val userId: String,
    val username: String,
    val bundle: KeyBundleBytes,
    val recoveryConfirmed: Boolean,
    val deletionDueAt: String?,
)

class AccountData(
    val userId: String,
    val username: String,
    val bundle: KeyBundleBytes,
    val recoveryConfirmed: Boolean?,
    val homeCollectionId: String?,
    val deletionDueAt: String?,
    val role: String? = null,
)

object AccountStorage {
    fun saveKeyBundle(rowStore: RowStorePort, bundle: KeyBundleBytes) {
        rowStore.kvSetJson(
            StorageKeys.ACCOUNT_KDF_PARAMS,
            buildJsonObject {
                put(KdfParamsKeys.SALT, encodeBytes(bundle.salt))
                put(KdfParamsKeys.OPS_LIMIT, bundle.opsLimit)
                put(KdfParamsKeys.MEM_LIMIT_BYTES, bundle.memLimitBytes)
            },
        )
        rowStore.kvSet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK, bundle.masterKeyByKek)
        rowStore.kvSet(StorageKeys.ACCOUNT_RECOVERY_KEY_BY_MASTER, bundle.recoveryKeyByMaster)
        rowStore.kvSet(StorageKeys.ACCOUNT_PUBLIC_KEY, bundle.publicKey)
        rowStore.kvSet(StorageKeys.ACCOUNT_PRIVATE_KEY_BY_MASTER, bundle.privateKeyByMaster)
    }

    fun saveAccountData(rowStore: RowStorePort, data: AccountData) {
        rowStore.kvSetJson(StorageKeys.ACCOUNT_USER_ID, JsonPrimitive(data.userId))
        rowStore.kvSetJson(StorageKeys.ACCOUNT_USERNAME, JsonPrimitive(data.username))
        saveKeyBundle(rowStore, data.bundle)
        data.recoveryConfirmed?.let { rowStore.kvSetJson(StorageKeys.ACCOUNT_RECOVERY_CONFIRMED, JsonPrimitive(it)) }
        data.homeCollectionId?.let { rowStore.kvSetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID, JsonPrimitive(it)) }
        saveDeletionDueAt(rowStore, data.deletionDueAt)
        data.role?.let { saveRole(rowStore, it) }
    }

    fun saveDeletionDueAt(rowStore: RowStorePort, deletionDueAt: String?) {
        if (deletionDueAt == null) {
            rowStore.kvDelete(StorageKeys.ACCOUNT_DELETION_DUE_AT)
        } else {
            rowStore.kvSetJson(StorageKeys.ACCOUNT_DELETION_DUE_AT, JsonPrimitive(deletionDueAt))
        }
    }

    fun saveRole(rowStore: RowStorePort, role: String) {
        rowStore.kvSetJson(StorageKeys.ACCOUNT_ROLE, JsonPrimitive(role))
    }

    fun loadAccount(rowStore: RowStorePort): StoredAccount? {
        val userId = readId(rowStore, StorageKeys.ACCOUNT_USER_ID)
        val username = readString(rowStore, StorageKeys.ACCOUNT_USERNAME)
        val kdfParams = readKdfParams(rowStore)
        val masterKeyByKek = rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK)
        val recoveryKeyByMaster = rowStore.kvGet(StorageKeys.ACCOUNT_RECOVERY_KEY_BY_MASTER)
        val publicKey = rowStore.kvGet(StorageKeys.ACCOUNT_PUBLIC_KEY)
        val privateKeyByMaster = rowStore.kvGet(StorageKeys.ACCOUNT_PRIVATE_KEY_BY_MASTER)
        if (
            userId == null ||
            username == null ||
            kdfParams == null ||
            masterKeyByKek == null ||
            recoveryKeyByMaster == null ||
            publicKey == null ||
            privateKeyByMaster == null
        ) {
            return null
        }
        val recoveryConfirmed = readBoolean(rowStore, StorageKeys.ACCOUNT_RECOVERY_CONFIRMED)
        val deletionDueAt = readString(rowStore, StorageKeys.ACCOUNT_DELETION_DUE_AT)
        return StoredAccount(
            userId = userId,
            username = username,
            bundle = KeyBundleBytes(
                salt = kdfParams.salt,
                opsLimit = kdfParams.opsLimit,
                memLimitBytes = kdfParams.memLimitBytes,
                masterKeyByKek = masterKeyByKek,
                recoveryKeyByMaster = recoveryKeyByMaster,
                publicKey = publicKey,
                privateKeyByMaster = privateKeyByMaster,
            ),
            recoveryConfirmed = recoveryConfirmed ?: true,
            deletionDueAt = deletionDueAt,
        )
    }

    fun readString(rowStore: RowStorePort, key: String): String? {
        val element = rowStore.kvGetJson(key) ?: return null
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(key)
    }

    fun readBoolean(rowStore: RowStorePort, key: String): Boolean? {
        val element = rowStore.kvGetJson(key) ?: return null
        return (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalid(key)
    }

    private fun readId(rowStore: RowStorePort, key: String): String? = readString(rowStore, key)?.also { if (!isValidId(it)) invalid(key) }

    private class StoredKdfParams(val salt: ByteArray, val opsLimit: Long, val memLimitBytes: Long)

    private fun readKdfParams(rowStore: RowStorePort): StoredKdfParams? {
        val key = StorageKeys.ACCOUNT_KDF_PARAMS
        val element = rowStore.kvGetJson(key) ?: return null
        val item = element as? JsonObject ?: invalid(key)
        val salt = (item[KdfParamsKeys.SALT] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(key)
        return StoredKdfParams(decodeBytes(salt), integer(item[KdfParamsKeys.OPS_LIMIT], key), integer(item[KdfParamsKeys.MEM_LIMIT_BYTES], key))
    }

    private fun integer(element: JsonElement?, key: String): Long {
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: invalid(key)
        if (value != Math.rint(value) || value.isInfinite()) invalid(key)
        return value.toLong()
    }

    private fun invalid(key: String): Nothing = throw IllegalStateException(AccountText.storedValueInvalid(key))
}
