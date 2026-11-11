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
        // ScrawlKit for debug builds, published to ~/.m2 from ~/dev/scrawl/android.
        mavenLocal { content { includeGroup("dev.kitlangton.scrawl") } }
        google()
        mavenCentral()
    }
}

rootProject.name = "sync-md"
include(":core", ":app")
