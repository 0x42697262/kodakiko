plugins {
    id("kodakiko.kmp.library")
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}
