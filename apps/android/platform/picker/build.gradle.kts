plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(libs.activity.compose)
    api(libs.androidx.documentfile)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
