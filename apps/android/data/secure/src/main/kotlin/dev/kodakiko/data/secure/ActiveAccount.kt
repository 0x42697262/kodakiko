package dev.kodakiko.data.secure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

enum class AccountMode(val wire: String) {
    SERVER("server"),
    LOCAL("local"),
    ;

    companion object {
        fun fromWire(wire: String): AccountMode? = entries.firstOrNull { it.wire == wire }
    }
}

data class ActiveAccount(val schemaVersion: Int, val accountKey: String, val username: String, val mode: AccountMode) {
    fun toJson(): String =
        buildJsonObject {
            put(ActiveAccountConstants.SCHEMA_VERSION, schemaVersion)
            put(ActiveAccountConstants.ACCOUNT_KEY, accountKey)
            put(ActiveAccountConstants.USERNAME, username)
            put(ActiveAccountConstants.MODE, mode.wire)
        }.toString()

    companion object {
        const val CURRENT_SCHEMA_VERSION = ActiveAccountConstants.CURRENT_SCHEMA_VERSION

        fun parse(json: String): ActiveAccount? {
            val item = try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (failure: IllegalArgumentException) {
                null
            } ?: return null
            val version = item.integer(ActiveAccountConstants.SCHEMA_VERSION) ?: return null
            if (version > CURRENT_SCHEMA_VERSION) return null
            val accountKey = item.string(ActiveAccountConstants.ACCOUNT_KEY) ?: return null
            val username = item.string(ActiveAccountConstants.USERNAME) ?: return null
            val mode = if (ActiveAccountConstants.MODE in item) {
                AccountMode.fromWire(item.string(ActiveAccountConstants.MODE) ?: return null) ?: return null
            } else {
                AccountMode.SERVER
            }
            return ActiveAccount(version, accountKey, username, mode)
        }

        private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.integer(name: String): Int? {
            val value = (get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
            return if (value == Math.rint(value) && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) value.toInt() else null
        }
    }
}
