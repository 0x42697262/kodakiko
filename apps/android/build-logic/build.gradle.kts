import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spotless)
}

val bytecodeTarget: String = libs.versions.jvm.target.get()

java {
    sourceCompatibility = JavaVersion.toVersion(bytecodeTarget)
    targetCompatibility = JavaVersion.toVersion(bytecodeTarget)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(bytecodeTarget))
    }
}

dependencyLocking {
    lockAllConfigurations()
}

spotless {
    val ktlintVersion = libs.ktlint.cli.get().version
    val editorConfig = file("../.editorconfig")
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencies {
    compileOnly(gradleKotlinDsl())
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    compileOnly(files(libs::class.java.superclass.protectionDomain.codeSource.location))
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "kodakiko.kmp.library"
            implementationClass = "dev.kodakiko.buildlogic.KmpLibraryPlugin"
        }
        register("androidLibrary") {
            id = "kodakiko.android.library"
            implementationClass = "dev.kodakiko.buildlogic.AndroidLibraryPlugin"
        }
        register("androidApplication") {
            id = "kodakiko.android.application"
            implementationClass = "dev.kodakiko.buildlogic.AndroidApplicationPlugin"
        }
        register("compose") {
            id = "kodakiko.compose"
            implementationClass = "dev.kodakiko.buildlogic.ComposePlugin"
        }
        register("jvmTooling") {
            id = "kodakiko.jvm.tooling"
            implementationClass = "dev.kodakiko.buildlogic.JvmToolingPlugin"
        }
        register("spotless") {
            id = "kodakiko.spotless"
            implementationClass = "dev.kodakiko.buildlogic.SpotlessPlugin"
        }
    }
}
