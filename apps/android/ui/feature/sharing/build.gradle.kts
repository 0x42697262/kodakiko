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
    api(projects.service.sharing)
    api(projects.service.shareout)
    api(projects.service.index)
    api(projects.shared.contract)
    api(projects.shared.index)
    api(libs.androidx.lifecycle.viewmodel.compose)
    implementation(projects.shared.js)
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
    testRuntimeOnly(libs.jna)
}
