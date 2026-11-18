plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.service.account)
    api(projects.service.entries)
    api(projects.service.sharing)
    api(projects.shared.contract)
    api(projects.shared.crypto)
    api(projects.data.db)
    api(projects.data.files)
    api(projects.data.media)
    api(projects.platform.picker)
    api(libs.kotlinx.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation(projects.shared.js)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.jna)
}
