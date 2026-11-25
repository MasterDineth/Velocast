pluginManagement {
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

rootProject.name = "VeloCast"
include(":protocol")
include(":server-core")
include(":client-core")
include(":launcher-adb")
include(":launcher-shizuku")
include(":launcher-root")
include(":designsystem")
include(":app-viewer")
include(":app-host")
