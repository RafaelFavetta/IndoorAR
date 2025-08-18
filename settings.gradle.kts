pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal() // Plugins externos
    }

    plugins {
        id("com.google.gms.google-services") version "4.4.3"
        id("org.jetbrains.kotlin.android") version "2.0.0"
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
