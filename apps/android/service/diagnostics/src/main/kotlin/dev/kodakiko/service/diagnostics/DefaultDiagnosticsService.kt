package dev.kodakiko.service.diagnostics

import dev.kodakiko.contract.API_VERSION
import dev.kodakiko.crypto.checkVectors
import dev.kodakiko.data.db.DeviceSettingsJson
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DefaultDiagnosticsService(private val deps: DiagnosticsDeps) : DiagnosticsService {
    override suspend fun runVectors(): VectorReport =
        withContext(deps.ioDispatcher) { VectorReport(checkVectors(deps.sodium) { name -> deps.vectors.read(name) }) }

    override fun debugInfoJson(): String {
        val config = deps.config()
        val info = JsonObject(
            linkedMapOf(
                DebugInfoKeys.APP to (deps.appVersion?.let(::JsonPrimitive) ?: JsonNull),
                DebugInfoKeys.PLATFORM to JsonPrimitive(DiagnosticsConstants.PLATFORM),
                DebugInfoKeys.OS to JsonPrimitive(deps.osVersion),
                DebugInfoKeys.SERVER to (config?.serverVersion?.let(::JsonPrimitive) ?: JsonNull),
                DebugInfoKeys.API to (config?.let { JsonPrimitive(API_VERSION) } ?: JsonNull),
                DebugInfoKeys.MODE to JsonPrimitive(deps.mode().wire),
                DebugInfoKeys.SETTINGS to DeviceSettingsJson.encode(deps.settings()),
            ),
        )
        return prettyJson(info)
    }
}
