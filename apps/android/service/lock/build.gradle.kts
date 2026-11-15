plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.service.account)
    api(projects.data.secure)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.shared.js)
    testImplementation(projects.platform.device)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
