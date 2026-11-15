plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(libs.camerax.core)
    api(libs.camerax.lifecycle)
    api(libs.camerax.video)
    implementation(libs.camerax.camera2)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
