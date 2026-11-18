package dev.kodakiko.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class ComposePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.kotlin.compose)
        pluginManager.withPlugin(libs.plugins.android.application.get().pluginId) {
            extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
        }
        pluginManager.withPlugin(libs.plugins.android.library.get().pluginId) {
            extensions.configure<LibraryExtension> { buildFeatures.compose = true }
        }
        configureComposeCompiler()
        val bom = dependencies.platform(libs.compose.bom)
        listOf("implementation", "testImplementation", "androidTestImplementation").forEach { configuration ->
            dependencies.add(configuration, bom)
        }
    }

    private fun Project.configureComposeCompiler() {
        val stabilityConfig = rootProject.layout.projectDirectory.file(BuildLogicConstants.COMPOSE_STABILITY_CONFIG)
        val reportsEnabled = providers.gradleProperty(BuildLogicConstants.COMPOSE_REPORTS_PROPERTY)
            .map { it.toBoolean() }
            .getOrElse(false)
        extensions.configure<ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(stabilityConfig)
            if (reportsEnabled) {
                val destination = layout.buildDirectory.dir(BuildLogicConstants.COMPOSE_REPORTS_DIR)
                reportsDestination.set(destination)
                metricsDestination.set(destination)
            }
        }
        tasks.withType<KotlinCompile>().configureEach { task ->
            task.inputs.file(stabilityConfig).withPathSensitivity(PathSensitivity.RELATIVE)
            if (reportsEnabled) task.incremental = false
        }
    }
}
