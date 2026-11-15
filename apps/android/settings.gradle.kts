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
include(":shared:js")
include(":shared:contract")
include(":shared:account")
include(":shared:testing")
include(":shared:media")
include(":shared:domain")
include(":shared:index")
include(":shared:sync")
include(":shared:export")
include(":shared:markdown")
include(":data:db")
include(":data:files")
include(":data:secure")
include(":data:migration")
include(":data:upload")
include(":data:media")
include(":data:net")
include(":platform:device")
include(":platform:location")
