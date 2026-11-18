package dev.kodakiko.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.kotlin.multiplatform)
        applyPlugin(libs.plugins.android.kotlin.multiplatform.library)
        applyKodakikoBase()
        extensions.configure<KotlinMultiplatformExtension> {
            jvm()
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryExtension> {
                namespace = kodakikoNamespace
                compileSdk = compileSdkVersion
                minSdk = minSdkVersion
                withHostTest { }
                withDeviceTest {
                    instrumentationRunner = BuildLogicConstants.INSTRUMENTATION_RUNNER
                }
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.kotlin.test)
            }
            sourceSets.getByName("jvmTest").dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
            sourceSets.getByName(BuildLogicConstants.ANDROID_DEVICE_TEST_SOURCE_SET).dependencies {
                implementation(libs.junit4)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
        tasks.named(BuildLogicConstants.JVM_TEST_TASK, Test::class.java) { it.useJUnitPlatform() }
    }
}
