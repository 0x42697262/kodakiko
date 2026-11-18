plugins {
    id("kodakiko.android.library")
    id("kodakiko.compose")
    alias(libs.plugins.roborazzi)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

dependencies {
    api(projects.ui.core)
    api(projects.service.account)
    api(projects.service.lock)
    api(projects.data.migration)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.biometric)
    implementation(projects.shared.crypto)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.metrox.viewmodel.compose)
    testImplementation(projects.data.db)
    testImplementation(projects.data.files)
    testImplementation(projects.shared.account)
    testImplementation(projects.shared.contract)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.activity.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testRuntimeOnly(libs.jna)
}
