plugins {
    id("kodakiko.android.library")
}

abstract class VectorAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val vectors: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val root = output.get().asFile
        root.listFiles()?.forEach { it.deleteRecursively() }
        val directory = root.resolve("vectors").apply { mkdirs() }
        vectors.forEach { source -> source.copyTo(directory.resolve(source.name)) }
    }
}

val vectorAssets = tasks.register<VectorAssets>("vectorAssets") {
    vectors.from(rootProject.layout.projectDirectory.dir("../../packages/core/vectors").asFileTree.matching { include("*.json") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(vectorAssets, VectorAssets::output)
    }
}

dependencies {
    api(projects.shared.crypto)
    api(projects.shared.contract)
    api(projects.data.db)
    api(projects.data.secure)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.jna)
}
