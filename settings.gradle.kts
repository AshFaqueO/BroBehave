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

rootProject.name = "BroBehave"

include(":app")
include(":core:model")
include(":core:data")
include(":core:database")
include(":core:network")
include(":core:designsystem")
include(":core:ui")
include(":core:testing")
include(":feature:onboarding")
include(":feature:auth")
include(":feature:feed")
include(":feature:profile")
include(":detection")
include(":baselineprofile")
