plugins {
    id("com.android.application") version "8.9.3" apply false
    id("com.android.library") version "8.9.3" apply false
    kotlin("android") version "1.9.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("com.google.gms.google-services") version "4.4.3" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
