plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
    testImplementation(libs.mockwebserver3)
}
