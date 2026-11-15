pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "kodakiko"

include(":app")
include(":tooling-tests")
include(":shared:crypto")
include(":shared:contract")
include(":shared:account")
include(":shared:testing")
include(":shared:media")
include(":shared:domain")
include(":data:db")
