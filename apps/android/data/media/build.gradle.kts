plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.shared.crypto)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.jna)
}
