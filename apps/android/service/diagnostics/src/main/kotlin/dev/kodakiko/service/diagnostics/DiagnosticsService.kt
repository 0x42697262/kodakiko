package dev.kodakiko.service.diagnostics

import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.VectorCaseResult
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode
import kotlinx.coroutines.CoroutineDispatcher

data class VectorReport(val cases: List<VectorCaseResult>) {
    val total: Int get() = cases.size

    val passed: Int get() = cases.count { it.passed }
}

interface DiagnosticsService {
    suspend fun runVectors(): VectorReport

    fun debugInfoJson(): String
}

class DiagnosticsDeps(
    val sodium: Sodium,
    val vectors: VectorSource,
    val appVersion: String?,
    val osVersion: Int,
    val config: () -> PublicConfigDto?,
    val mode: () -> AccountMode,
    val settings: () -> DeviceSettings,
    val ioDispatcher: CoroutineDispatcher,
)
