package dev.kodakiko.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType
import org.gradle.plugin.use.PluginDependency
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

internal val Project.libs: LibrariesForLibs get() = the<LibrariesForLibs>()

internal fun Project.applyPlugin(plugin: Provider<PluginDependency>) {
    pluginManager.apply(plugin.get().pluginId)
}

internal fun Project.applyKodakikoBase() {
    pluginManager.apply(SpotlessPlugin::class.java)
    dependencyLocking { it.lockAllConfigurations() }
    tasks.withType<KotlinJvmCompile>().configureEach {
        it.compilerOptions.jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
    }
    val testRuntime = JavaLanguageVersion.of(libs.versions.jvm.test.runtime.get())
    tasks.withType<Test>().configureEach { test ->
        test.javaLauncher.set(
            extensions.getByType<JavaToolchainService>().launcherFor { it.languageVersion.set(testRuntime) },
        )
        test.jvmArgs(BuildLogicConstants.ROBOLECTRIC_JVM_ARGS)
    }
}

internal val Project.jvmTargetVersion: String get() = libs.versions.jvm.target.get()

internal val Project.javaVersion: JavaVersion get() = JavaVersion.toVersion(jvmTargetVersion)

internal val Project.compileSdkVersion: Int get() = libs.versions.android.compile.sdk.get().toInt()

internal val Project.minSdkVersion: Int get() = libs.versions.android.min.sdk.get().toInt()

internal val Project.targetSdkVersion: Int get() = libs.versions.android.target.sdk.get().toInt()

internal val Project.kodakikoNamespace: String
    get() = (listOf(BuildLogicConstants.NAMESPACE_ROOT) + path.removePrefix(":").split(':'))
        .joinToString(".") { it.replace('-', '_') }

internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.namespace = kodakikoNamespace
    android.compileSdk = compileSdkVersion
    android.defaultConfig.minSdk = minSdkVersion
    android.compileOptions.sourceCompatibility = javaVersion
    android.compileOptions.targetCompatibility = javaVersion
    android.testOptions.unitTests.isIncludeAndroidResources = true
    android.lint.abortOnError = true
    android.lint.disable += BuildLogicConstants.LINT_VERSION_CHECKS
}
