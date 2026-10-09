pluginManagement {
    repositories {
        google()
        // Fallback mirror for Maven Central when the primary endpoint is unavailable.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        // Fallback mirror for Maven Central when the primary endpoint is unavailable.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "VVSU-Schedule"

include(":app")
