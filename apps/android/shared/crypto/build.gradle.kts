plugins {
    id("kodakiko.kmp.library")
}

val coreVectors = rootProject.layout.projectDirectory.dir("../../packages/core/vectors")

val vectorResources = tasks.register<Sync>("vectorResources") {
    into(layout.buildDirectory.dir("generated/vectorResources"))
    from(coreVectors) {
        include("*.json")
        into("vectors")
    }
}

val jna = libs.jna.get()

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.js)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.lazysodium.java)
            implementation(libs.jna)
        }
        androidMain.dependencies {
            implementation(libs.lazysodium.android.get().toString()) {
                exclude(group = jna.module.group, module = jna.module.name)
            }
            implementation("$jna@aar")
        }
        jvmTest {
            resources.srcDir(vectorResources)
        }
        getByName("androidDeviceTest") {
            resources.srcDir(vectorResources)
        }
    }
}

val lazysodiumBytecode = libs.versions.jvm.test.runtime.get().toInt()

configurations
    .matching { it.name.startsWith("jvm") && (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath")) }
    .configureEach {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, lazysodiumBytecode)
    }
