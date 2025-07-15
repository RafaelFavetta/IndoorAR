plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services") // 🧩 Firebase plugin aqui!
}

android {
    namespace = "com.example.indoorar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.indoorar"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
    }

    flavorDimensions += "default"

    productFlavors {
        create("dev") {
            dimension = "default"
            resourceConfigurations.addAll(listOf("en", "xxhdpi"))
        }
        create("full") {
            dimension = "default"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }
}

dependencies {
    // Android
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Jetpack Compose (caso use no futuro)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.3")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.3")

    // Firebase Auth
    implementation("com.google.firebase:firebase-auth-ktx:23.2.1")

    // Firebase BOM (controla versões centralizadas)
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))

    // Se quiser adicionar Firestore depois:
    // implementation("com.google.firebase:firebase-firestore-ktx")

    // Testes
    testImplementation("junit:junit:4.13.2")
}
