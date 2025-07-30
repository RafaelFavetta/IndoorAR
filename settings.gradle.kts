pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal() // Plugins externos
    }

    plugins {
        id("com.google.gms.google-services") version "4.4.3"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "IndoorAR"
include(":app")
