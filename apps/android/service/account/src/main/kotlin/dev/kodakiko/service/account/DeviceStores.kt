package dev.kodakiko.service.account

import android.content.Context
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.data.files.AccountPaths
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.files.accountHash
import kotlinx.serialization.json.JsonElement
import java.io.File

class DatabaseRowStore(private val context: Context, private val sodium: Sodium) : RowStorePort {
    private val lock = Any()

    @Volatile
    private var database: KodakikoDatabase? = null

    fun current(): KodakikoDatabase? = database

    override fun open(accountKey: String) {
        synchronized(lock) {
            close()
            database = KodakikoDatabase.open(context, accountHash(sodium, accountKey))
        }
    }

    override fun close() {
        synchronized(lock) {
            val open = database
            database = null
            open?.close()
        }
    }

    override fun destroy(accountKey: String) {
        synchronized(lock) {
            close()
            val file = KodakikoDatabase.databaseFile(context, accountHash(sodium, accountKey))
            file.delete()
            StoreConstants.DATABASE_SIDE_FILE_SUFFIXES.forEach { File(file.path + it).delete() }
        }
    }

    override fun <T> transaction(work: (RowStoreTx) -> T): T = requireDatabase().transaction(work)

    override fun kvGet(key: String): ByteArray? = requireDatabase().kvGet(key)

    override fun kvGetJson(key: String): JsonElement? = requireDatabase().kvGetJson(key)

    override fun kvSet(key: String, value: ByteArray) {
        requireDatabase().kvSet(key, value)
    }

    override fun kvSetJson(key: String, value: JsonElement) {
        requireDatabase().kvSetJson(key, value)
    }

    override fun kvDelete(key: String) {
        requireDatabase().kvDelete(key)
    }

    private fun requireDatabase(): KodakikoDatabase = database ?: throw IllegalStateException(AccountText.ROW_STORE_NOT_OPEN)
}

class DiskFileStore(private val context: Context, private val sodium: Sodium) : FileStorePort {
    private val lock = Any()

    @Volatile
    private var store: FileStore? = null

    fun current(): FileStore? = store

    fun require(): FileStore = store ?: throw IllegalStateException(AccountText.FILE_STORE_NOT_OPEN)

    override fun open(accountKey: String) {
        synchronized(lock) {
            store = FileStore.open(context, accountHash(sodium, accountKey))
        }
    }

    override fun destroy(accountKey: String) {
        synchronized(lock) {
            store = null
            val root = AccountPaths(context, accountHash(sodium, accountKey)).root
            if (root.exists()) root.deleteRecursively()
        }
    }
}
