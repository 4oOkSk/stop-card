pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Legacy Xposed API, compile-only; LSPosed/Vector provides it at runtime.
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "StopCard"

include(":app")
include(":testbed")
