plugins {
    id("kodakiko.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.crypto)
        }
    }
}

val lazysodiumBytecode = libs.versions.jvm.test.runtime.get().toInt()

configurations
    .matching { it.name.startsWith("jvm") && (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath")) }
    .configureEach {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, lazysodiumBytecode)
    }
