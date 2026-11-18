plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.service.account)
    api(projects.shared.index)
    api(projects.shared.contract)
    api(projects.shared.crypto)
    api(projects.shared.domain)
    api(projects.data.db)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.shared.js)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.jna)
}
