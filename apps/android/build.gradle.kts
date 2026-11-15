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
        ":shared:js:jvmTest",
        ":shared:js:testAndroidHostTest",
        ":shared:contract:jvmTest",
        ":shared:contract:testAndroidHostTest",
        ":shared:account:jvmTest",
        ":shared:account:testAndroidHostTest",
        ":shared:testing:jvmTest",
        ":shared:testing:testAndroidHostTest",
        ":shared:media:jvmTest",
        ":shared:media:testAndroidHostTest",
        ":shared:domain:jvmTest",
        ":shared:domain:testAndroidHostTest",
        ":shared:index:jvmTest",
        ":shared:index:testAndroidHostTest",
        ":shared:sync:jvmTest",
        ":shared:sync:testAndroidHostTest",
        ":shared:export:jvmTest",
        ":shared:export:testAndroidHostTest",
        ":shared:markdown:jvmTest",
        ":shared:markdown:testAndroidHostTest",
        ":data:db:testDebugUnitTest",
        ":data:files:testDebugUnitTest",
        ":data:migration:testDebugUnitTest",
        ":data:upload:testDebugUnitTest",
        ":data:media:testDebugUnitTest",
        ":data:net:testDebugUnitTest",
        ":platform:device:testDebugUnitTest",
        ":platform:location:testDebugUnitTest",
        ":platform:camera:testDebugUnitTest",
        ":platform:picker:testDebugUnitTest",
        ":service:account:testDebugUnitTest",
        ":service:lock:testDebugUnitTest",
        ":service:index:testDebugUnitTest",
        ":service:entries:testDebugUnitTest",
        ":service:sync:testDebugUnitTest",
        ":app:testDebugUnitTest",
        ":app:testReleaseUnitTest",
        ":app:lintRelease",
    )
}
