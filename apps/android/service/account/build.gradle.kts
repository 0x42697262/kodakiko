plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.shared.account)
    api(projects.shared.crypto)
    api(projects.shared.contract)
    api(projects.data.net)
    api(projects.data.db)
    api(projects.data.secure)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(projects.data.files)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.jna)
}
