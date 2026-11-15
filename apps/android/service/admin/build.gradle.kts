plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.shared.contract)
    api(projects.data.net)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.mockwebserver3)
}
