plugins {
    id("kodakiko.android.library")
    id("kodakiko.compose")
    alias(libs.plugins.roborazzi)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

dependencies {
    api(projects.data.db)
    api(projects.service.stats)
    api(projects.service.sync)
    api(projects.service.sharing)
    api(projects.service.account)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(projects.shared.domain)
    implementation(projects.shared.js)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.activity.compose)
    testImplementation(libs.compose.foundation)
    testImplementation(libs.compose.material3)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
