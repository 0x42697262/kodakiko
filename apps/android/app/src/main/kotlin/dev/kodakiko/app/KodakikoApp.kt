package dev.kodakiko.app

import android.app.Application
import dev.kodakiko.app.di.AppGraph
import dev.kodakiko.app.log.CrashRecorder
import dev.zacsweers.metro.createGraphFactory

open class KodakikoApp : Application() {
    val graph: AppGraph by lazy { createGraphFactory<AppGraph.Factory>().create(this) }

    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(graph.debugLogFile)
    }
}
