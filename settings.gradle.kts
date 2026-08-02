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
        // Central first, and google() is content-filtered to Android-only groups.
        // This keeps the pure-JVM :core module resolvable without ever touching
        // Google's Maven host, so core logic can be built and tested in
        // restricted-network environments.
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "ThorUtils"

include(":core")

// The Android module needs the Android SDK and Google's Maven host. Setting
// `-PthorCoreOnly` drops it from the build so the pure-JVM core logic can be
// compiled and tested anywhere — including sandboxes without SDK access.
if (!providers.gradleProperty("thorCoreOnly").isPresent) {
    include(":app")
}
