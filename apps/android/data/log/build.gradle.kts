plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.shared.log)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
