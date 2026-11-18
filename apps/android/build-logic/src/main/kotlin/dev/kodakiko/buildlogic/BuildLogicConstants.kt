package dev.kodakiko.buildlogic

internal object BuildLogicConstants {
    const val NAMESPACE_ROOT = "dev.kodakiko"
    const val VERSION_CODE_MAJOR_WEIGHT = 10_000
    const val VERSION_CODE_MINOR_WEIGHT = 100
    const val VERSION_NAME_PARTS = 3
    const val EDITOR_CONFIG = ".editorconfig"
    const val KOTLIN_SOURCES = "src/**/*.kt"
    const val GRADLE_SCRIPTS = "*.gradle.kts"
    const val JVM_TEST_TASK = "jvmTest"
    const val ANDROID_DEVICE_TEST_SOURCE_SET = "androidDeviceTest"
    const val INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
    const val COMPOSE_REPORTS_PROPERTY = "kodakiko.composeReports"
    const val COMPOSE_REPORTS_DIR = "compose-reports"
    const val COMPOSE_STABILITY_CONFIG = "stability_config.conf"
    val LINT_VERSION_CHECKS = setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    val ROBOLECTRIC_JVM_ARGS = listOf("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}
