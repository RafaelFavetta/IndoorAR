plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
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
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    //ZXing para scan de QR
    implementation ("com.journeyapps:zxing-android-embedded:4.3.0")

    //SceneForm para o ARCore
    dependencies {
        implementation("com.gorisse.thomas.sceneform:sceneform:1.23.0")
        implementation("com.gorisse.thomas.sceneform:ux:1.23.0")
        implementation("com.google.ar:core:1.50.0")
    }



    // Máscaras de editText
    implementation("com.redmadrobot:input-mask-android:6.1.0")


    // Jetpack Compose
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.3")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.3")

    // Firebase BOM — controla versões
    implementation(platform("com.google.firebase:firebase-bom:32.7.3"))

    // Firebase Core
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Firebase Auth e Firestore
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.activity:activity:1.10.1")

    // Testes
    testImplementation("junit:junit:4.13.2")
}

apply(plugin = "com.google.gms.google-services")