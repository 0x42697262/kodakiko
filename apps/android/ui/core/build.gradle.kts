plugins {
    id("kodakiko.android.library")
    id("kodakiko.compose")
}

dependencies {
    api(projects.data.db)
    api(projects.service.stats)
    api(projects.service.sync)
    api(projects.service.sharing)
    api(libs.compose.ui)
    implementation(projects.shared.domain)
    implementation(projects.shared.js)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.activity.compose)
    testImplementation(libs.compose.foundation)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.serialization.json)
}
