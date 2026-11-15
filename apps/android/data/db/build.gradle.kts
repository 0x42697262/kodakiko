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
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.jna)
}
