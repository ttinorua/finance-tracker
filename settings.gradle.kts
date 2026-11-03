pluginManagement {
    repositories {
        google()
        // Google's official copy of Maven Central, tried first: Maven Central itself often turns
        // fresh cloud build machines away with HTTP 429 (too many requests). Same files; Maven
        // Central stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's official copy of Maven Central, tried first: Maven Central itself often turns
        // fresh cloud build machines away with HTTP 429 (too many requests). Same files; Maven
        // Central stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "PersonalFinanceTracker"
include(":app")
