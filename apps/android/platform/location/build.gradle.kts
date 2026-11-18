plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.shared.domain)
    api(projects.shared.media)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
