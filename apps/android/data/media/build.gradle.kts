plugins {
    id("kodakiko.android.library")
}

abstract class DeviceTestFixtureAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fixture: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val source = fixture.get().asFile
        val directory = output.get().asFile
        directory.listFiles()?.forEach { it.deleteRecursively() }
        source.copyTo(directory.resolve(source.name))
    }
}

val deviceTestFixtureAssets = tasks.register<DeviceTestFixtureAssets>("deviceTestFixtureAssets") {
    fixture.set(rootProject.layout.projectDirectory.file("../../tests/fixtures/media/video-20s.mp4"))
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addGeneratedSourceDirectory(deviceTestFixtureAssets, DeviceTestFixtureAssets::output)
        }
    }
}

dependencies {
    api(projects.shared.crypto)
    api(projects.data.db)
    api(projects.data.files)
    api(projects.data.net)
    api(libs.media3.exoplayer)
    testImplementation(libs.junit4)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.jna)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
