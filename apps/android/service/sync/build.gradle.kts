plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.service.account)
    api(projects.service.entries)
    api(projects.shared.contract)
    api(projects.shared.crypto)
    api(projects.shared.sync)
    api(projects.data.db)
    api(projects.data.files)
    api(projects.data.net)
    api(projects.data.secure)
    api(projects.data.upload)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(projects.shared.js)
    implementation(projects.shared.domain)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    testRuntimeOnly(libs.jna)
}
