plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.service.account)
    api(projects.service.entries)
    api(projects.service.sync)
    api(projects.shared.account)
    api(projects.shared.contract)
    api(projects.shared.crypto)
    api(projects.data.db)
    api(projects.data.files)
    api(projects.data.net)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(projects.shared.js)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.jna)
}
