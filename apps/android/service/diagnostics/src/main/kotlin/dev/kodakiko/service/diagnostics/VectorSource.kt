package dev.kodakiko.service.diagnostics

import android.content.Context

fun interface VectorSource {
    fun read(name: String): String
}

class AssetVectorSource(context: Context) : VectorSource {
    private val assets = context.applicationContext.assets

    override fun read(name: String): String =
        assets.open(DiagnosticsConstants.VECTOR_ASSET_DIRECTORY + name).use { it.readBytes().decodeToString() }
}
