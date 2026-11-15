plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.process)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
