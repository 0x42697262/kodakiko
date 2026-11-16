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
include(":platform:audio")
include(":platform:camera")
include(":platform:picker")
include(":service:account")
include(":service:lock")
include(":service:index")
include(":service:entries")
include(":service:sync")
include(":service:sharing")
include(":service:links")
include(":service:admin")
include(":service:stats")
include(":service:diagnostics")
include(":service:shareout")
include(":service:backup")
include(":ui:core")
include(":ui:feature:auth")
include(":ui:feature:lock")
include(":e2e")
