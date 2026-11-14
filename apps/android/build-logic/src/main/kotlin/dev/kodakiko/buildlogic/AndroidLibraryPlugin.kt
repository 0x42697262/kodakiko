package dev.kodakiko.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.android.library)
        applyKodakikoBase()
        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
        }
    }
}
