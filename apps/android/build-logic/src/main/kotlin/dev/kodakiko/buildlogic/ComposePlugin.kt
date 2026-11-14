package dev.kodakiko.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class ComposePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.kotlin.compose)
        pluginManager.withPlugin(libs.plugins.android.application.get().pluginId) {
            extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
        }
        pluginManager.withPlugin(libs.plugins.android.library.get().pluginId) {
            extensions.configure<LibraryExtension> { buildFeatures.compose = true }
        }
        val bom = dependencies.platform(libs.compose.bom)
        listOf("implementation", "testImplementation", "androidTestImplementation").forEach { configuration ->
            dependencies.add(configuration, bom)
        }
    }
}
