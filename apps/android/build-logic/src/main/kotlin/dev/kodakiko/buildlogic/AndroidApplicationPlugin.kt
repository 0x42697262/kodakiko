package dev.kodakiko.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        applyPlugin(libs.plugins.android.application)
        applyKodakikoBase()
        val name = libs.versions.app.version.name.get()
        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig.targetSdk = targetSdkVersion
            defaultConfig.versionName = name
            defaultConfig.versionCode = versionCodeOf(name)
            buildFeatures.buildConfig = true
        }
    }

    private fun versionCodeOf(versionName: String): Int {
        val parts = versionName.split('.').map { it.toInt() }
        require(parts.size == BuildLogicConstants.VERSION_NAME_PARTS) { "versionName must be major.minor.patch: $versionName" }
        val (major, minor, patch) = parts
        return major * BuildLogicConstants.VERSION_CODE_MAJOR_WEIGHT +
            minor * BuildLogicConstants.VERSION_CODE_MINOR_WEIGHT +
            patch
    }
}
