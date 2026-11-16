package dev.kodakiko.app

import android.app.Application
import dev.kodakiko.app.di.AppGraph
import dev.zacsweers.metro.createGraphFactory

open class KodakikoApp : Application() {
    val graph: AppGraph by lazy { createGraphFactory<AppGraph.Factory>().create(this) }
}
