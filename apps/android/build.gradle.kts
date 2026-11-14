plugins {
    id("kodakiko.spotless")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.register("verify") {
    group = "verification"
    description = "Runs every check of the Kotlin client: formatting, tooling tests, unit tests and lint."
    dependsOn(gradle.includedBuild("build-logic").task(":spotlessCheck"))
    dependsOn(
        provider {
            allprojects.filter { it.plugins.hasPlugin("com.diffplug.spotless") }.map { "${it.path.removeSuffix(":")}:spotlessCheck" }
        },
    )
    dependsOn(
        ":tooling-tests:test",
        ":shared:crypto:jvmTest",
        ":shared:crypto:testAndroidHostTest",
        ":data:db:testDebugUnitTest",
        ":app:testDebugUnitTest",
        ":app:testReleaseUnitTest",
        ":app:lintRelease",
    )
}
