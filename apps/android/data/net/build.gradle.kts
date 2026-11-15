import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("kodakiko.android.library")
}

dependencies {
    api(projects.shared.contract)
    api(libs.okhttp)
    implementation(projects.shared.js)
    testImplementation(projects.shared.testing) {
        attributes { attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm) }
    }
    testImplementation(libs.junit4)
    testImplementation(libs.mockwebserver3)
}
