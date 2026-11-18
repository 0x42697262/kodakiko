plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.data.secure)
    api(projects.shared.crypto)
    implementation(projects.data.db)
    implementation(projects.data.files)
    implementation(projects.shared.contract)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(projects.shared.account)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.jna)
}
