plugins {
    id("kodakiko.android.library")
}

dependencies {
    testImplementation(projects.service.account)
    testImplementation(projects.service.entries)
    testImplementation(projects.service.sync)
    testImplementation(projects.service.sharing)
    testImplementation(projects.service.links)
    testImplementation(projects.shared.account)
    testImplementation(projects.shared.contract)
    testImplementation(projects.shared.crypto)
    testImplementation(projects.shared.domain)
    testImplementation(projects.shared.media)
    testImplementation(projects.data.db)
    testImplementation(projects.data.files)
    testImplementation(projects.data.media)
    testImplementation(projects.data.net)
    testImplementation(projects.data.secure)
    testImplementation(projects.data.upload)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.jna)
}

tasks.withType<Test>().configureEach {
    outputs.upToDateWhen { false }
}
