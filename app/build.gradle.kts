plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}


android {
    namespace = "com.example.indoorar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.indoorar"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true

        vectorDrawables {
            useSupportLibrary = true
        }
    }


    flavorDimensions += "default"
    productFlavors {
        create("dev") { dimension = "default" }
        create("full") { dimension = "default" }
    }

    buildTypes {
        release {
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

    lint {
        abortOnError = false
        warningsAsErrors = false
    }

}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    //Gson
    implementation("com.google.code.gson:gson:2.13.2")

    //Google Material
    implementation("com.google.android.material:material:1.13.0")

    //API Google (Play Services Auth ainda pode ser usada por compatibilidade)
    implementation("com.google.android.gms:play-services-auth:21.4.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ZXing
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // CameraX
    implementation("androidx.camera:camera-core:1.5.0")
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("androidx.camera:camera-extensions:1.5.0")

    // AR / SceneView
    implementation("com.google.ar:core:1.50.0")
    implementation("io.github.sceneview:sceneview:2.3.0")
    implementation("io.github.sceneview:arsceneview:2.3.0")


    // Compose
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui:1.9.2")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.2")

    // Layout pra base da home (os icones de app e etc)
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("com.google.android.material:material:1.13.0")


    // Firebase BOM
    implementation(platform("com.google.firebase:firebase-bom:32.7.3")) //só essa versão que não tá dando erro
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")

    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.activity:activity:1.11.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // Fragment KTX
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // Novo Google Sign-In (Credential Manager + Google Identity Services)
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("de.hdodenhof:circleimageview:3.1.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // iTextPDF para geração de PDF
    implementation("com.itextpdf:itextg:5.5.10")

    implementation("com.github.bumptech.glide:glide:5.0.5")
    kapt("com.github.bumptech.glide:compiler:5.0.5")

    implementation("com.github.yalantis:ucrop:2.2.8")


    testImplementation("junit:junit:4.13.2")

    // Firebase UI Storage for Glide <-> StorageReference support
    implementation("com.firebaseui:firebase-ui-storage:8.0.2")
}