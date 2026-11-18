package dev.kodakiko.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

class JvmToolingPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.kotlin.jvm)
        applyKodakikoBase()
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        dependencies.add("testImplementation", libs.junit.jupiter)
        dependencies.add("testImplementation", libs.kotest.assertions.core)
        dependencies.add("testRuntimeOnly", libs.junit.platform.launcher)
        tasks.withType<Test>().configureEach { it.useJUnitPlatform() }
    }
}
