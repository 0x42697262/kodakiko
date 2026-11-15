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
    fixtures.from(listOf("photo-gps.jpg").map { media.file(it) })
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addGeneratedSourceDirectory(deviceTestFixtureAssets, DeviceTestFixtureAssets::output)
        }
    }
}

dependencies {
    api(projects.service.account)
    api(projects.service.entries)
    api(projects.service.sync)
    api(projects.shared.account)
    api(projects.shared.contract)
    api(projects.shared.crypto)
    api(projects.shared.media)
    api(projects.data.db)
    api(projects.data.files)
    api(projects.data.media)
    api(projects.data.net)
    api(projects.data.upload)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(projects.shared.js)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.jna)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
