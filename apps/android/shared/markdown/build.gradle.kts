plugins {
    id("kodakiko.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.js)
        }
        jvmTest.dependencies {
            implementation(projects.shared.testing)
        }
    }
}
