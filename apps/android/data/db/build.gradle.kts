import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("kodakiko.android.library")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("LegacySchema") {
            packageName.set("dev.kodakiko.data.db.sql")
        }
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.sqldelight.android.driver)
    testImplementation(projects.shared.crypto)
    testImplementation(projects.shared.contract)
    testImplementation(projects.shared.account)
    testImplementation(projects.shared.testing) {
        attributes { attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm) }
    }
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.jna)
}
