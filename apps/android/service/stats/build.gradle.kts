plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.service.account)
    api(projects.service.entries)
    api(projects.shared.contract)
    api(projects.shared.index)
    api(projects.data.db)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.shared.js)
    implementation(projects.shared.domain)
    implementation(projects.shared.media)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.jna)
}
