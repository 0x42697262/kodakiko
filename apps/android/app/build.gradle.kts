plugins {
    id("kodakiko.android.application")
    id("kodakiko.compose")
    alias(libs.plugins.metro)
}

android {
    defaultConfig {
        applicationId = "com.x42697262.kodakiko"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".next"
        }
    }
}

dependencies {
    implementation(projects.ui.core)
    implementation(projects.ui.feature.auth)
    implementation(projects.ui.feature.lock)
    implementation(projects.ui.feature.gallery)
    implementation(projects.service.account)
    implementation(projects.service.lock)
    implementation(projects.service.index)
    implementation(projects.service.entries)
    implementation(projects.data.media)
    implementation(projects.data.db)
    implementation(projects.data.files)
    implementation(projects.data.secure)
    implementation(projects.data.net)
    implementation(projects.data.migration)
    implementation(projects.platform.device)
    implementation(projects.shared.crypto)
    implementation(projects.shared.contract)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.voyager.navigator)
    implementation(libs.voyager.tab.navigator)
    implementation(libs.voyager.transitions)
    implementation(libs.metro.runtime)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.jna)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
