plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.service.account)
    api(projects.service.entries)
    api(projects.service.lock)
    api(projects.shared.contract)
    api(projects.shared.crypto)
    api(projects.shared.export)
    api(projects.shared.domain)
    api(projects.data.db)
    api(projects.data.files)
    api(projects.data.media)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.shared.js)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.jna)
}
