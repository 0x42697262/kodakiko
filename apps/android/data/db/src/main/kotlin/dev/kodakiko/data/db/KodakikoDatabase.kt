package dev.kodakiko.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.kodakiko.data.db.sql.LegacySchema
import kotlinx.serialization.json.JsonElement
import java.io.File

class KodakikoDatabase private constructor(private val driver: SqlDriver) : AutoCloseable {
    private val schema = LegacySchema(driver)
    private val queries = schema.legacyQueries
    private val tx = RowStoreTx(queries)

    fun <T> transaction(work: (RowStoreTx) -> T): T = schema.transactionWithResult { work(tx) }

    fun kvGet(key: String): ByteArray? = queries.kvGet(key).executeAsOneOrNull()

    fun kvGetJson(key: String): JsonElement? = kvGet(key)?.let(JsonCodec::decodeBytes)

    fun kvSet(key: String, value: ByteArray) {
        queries.kvSet(key, value)
    }

    fun kvSetJson(key: String, value: JsonElement) {
        kvSet(key, JsonCodec.encodeBytes(value))
    }

    fun kvDelete(key: String) {
        queries.kvDelete(key)
    }

    fun pragma(name: String): String? {
        require(DbConstants.PRAGMA_NAME.matches(name)) { DbText.invalidPragma(name) }
        return driver.executeQuery(
            null,
            DbConstants.PRAGMA_PREFIX + name,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(DbConstants.PRAGMA_RESULT_COLUMN) else null) },
            DbConstants.PRAGMA_ARGUMENT_COUNT,
        ).value
    }

    override fun close() {
        driver.close()
    }

    companion object {
        fun databaseFile(context: Context, accountHash: String): File {
            require(DbConstants.ACCOUNT_HASH.matches(accountHash)) { DbText.invalidAccountHash(accountHash) }
            return File(File(context.filesDir, DbConstants.DATABASE_DIRECTORY), DbConstants.DATABASE_FILE_PREFIX + accountHash + DbConstants.DATABASE_FILE_EXTENSION)
        }

        fun open(context: Context, accountHash: String): KodakikoDatabase {
            val file = databaseFile(context, accountHash)
            if (file.exists()) refuseNewerSchema(file)
            file.parentFile?.mkdirs()
            val database = KodakikoDatabase(
                AndroidSqliteDriver(
                    schema = LegacySchema.Schema,
                    context = context,
                    name = file.absolutePath,
                    callback = LegacyCallback(),
                ),
            )
            try {
                database.pragma(DbConstants.USER_VERSION_PRAGMA)
            } catch (failure: Throwable) {
                database.close()
                throw failure
            }
            return database
        }

        private fun refuseNewerSchema(file: File) {
            val version = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version.toLong() }
            if (version > DbConstants.SCHEMA_VERSION) throw NewerSchemaError(version)
        }
    }

    private class LegacyCallback : AndroidSqliteDriver.Callback(LegacySchema.Schema) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            db.enableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
        }
    }
}
