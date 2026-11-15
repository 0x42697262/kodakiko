plugins {
    id("kodakiko.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.crypto)
            implementation(projects.shared.js)
            api(libs.kotlinx.serialization.json)
        }
    }
}

val lazysodiumBytecode = libs.versions.jvm.test.runtime.get().toInt()

configurations
    .matching { it.name.startsWith("jvm") && (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath")) }
    .configureEach {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, lazysodiumBytecode)
    }
