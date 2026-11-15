plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.shared.crypto)
    implementation(projects.shared.contract)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.jna)
}
