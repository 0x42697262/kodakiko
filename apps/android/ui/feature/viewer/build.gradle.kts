plugins {
    id("kodakiko.android.library")
    id("kodakiko.compose")
    alias(libs.plugins.roborazzi)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

abstract class ViewerDeviceTestAssets : DefaultTask() {
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

val viewerDeviceTestAssets = tasks.register<ViewerDeviceTestAssets>("viewerDeviceTestAssets") {
    fixtures.from(rootProject.layout.projectDirectory.dir("../../tests/fixtures/media").file("video-20s.mp4"))
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addGeneratedSourceDirectory(viewerDeviceTestAssets, ViewerDeviceTestAssets::output)
        }
    }
}

dependencies {
    api(projects.ui.core)
    api(projects.service.account)
    api(projects.service.index)
    api(projects.service.entries)
    api(projects.service.stats)
    api(projects.shared.contract)
    api(projects.shared.index)
    api(projects.shared.markdown)
    api(projects.platform.device)
    api(libs.media3.exoplayer)
    api(libs.androidx.lifecycle.viewmodel.compose)
    implementation(projects.shared.domain)
    implementation(projects.shared.js)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(libs.metrox.viewmodel.compose)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.activity.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    androidTestImplementation(projects.data.media)
    androidTestImplementation(projects.shared.crypto)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.activity.compose)
}
