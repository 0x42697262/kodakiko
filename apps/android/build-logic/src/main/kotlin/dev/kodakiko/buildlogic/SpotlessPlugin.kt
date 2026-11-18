package dev.kodakiko.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class SpotlessPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.spotless)
        val ktlintVersion = requireNotNull(libs.ktlint.cli.get().version)
        val editorConfig = rootProject.layout.projectDirectory.file(BuildLogicConstants.EDITOR_CONFIG).asFile
        extensions.configure<SpotlessExtension> {
            kotlin {
                it.target(BuildLogicConstants.KOTLIN_SOURCES)
                it.ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
                it.trimTrailingWhitespace()
                it.endWithNewline()
            }
            kotlinGradle {
                it.target(BuildLogicConstants.GRADLE_SCRIPTS)
                it.ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
                it.trimTrailingWhitespace()
                it.endWithNewline()
            }
        }
    }
}
