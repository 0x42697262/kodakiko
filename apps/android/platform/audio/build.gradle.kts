plugins {
    id("kodakiko.android.library")
}

abstract class DeviceTestFixtureAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fixtures: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val directory = output.get().asFile
        directory.listFiles()?.forEach { it.deleteRecursively() }
        fixtures.forEach { source -> source.copyTo(directory.resolve(source.name)) }
    }
}

val deviceTestFixtureAssets = tasks.register<DeviceTestFixtureAssets>("deviceTestFixtureAssets") {
    val media = rootProject.layout.projectDirectory.dir("../../tests/fixtures/media")
    fixtures.from(listOf("voice-opus.webm").map { media.file(it) })
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addGeneratedSourceDirectory(deviceTestFixtureAssets, DeviceTestFixtureAssets::output)
        }
    }
}

dependencies {
    api(libs.media3.exoplayer)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
