plugins {
    id("kodakiko.android.library")
}

dependencies {
    implementation(projects.shared.crypto)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
