plugins {
    id("kodakiko.android.application")
    id("kodakiko.compose")
}

android {
    defaultConfig {
        applicationId = "com.x42697262.kodakiko"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".next"
        }
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
}
