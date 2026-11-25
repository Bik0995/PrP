plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.prp.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.prp.android"
        minSdk = 29          // Android 10 : DNS privé imposé par le propriétaire de l'appareil
        targetSdk = 34
        versionCode = 5
        versionName = "1.0-a5"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Clé d'ESSAI, fournie pour que toutes les compilations aient la même signature
    // (sinon une mise à jour serait refusée). À remplacer par une clé privée avant publication.
    signingConfigs {
        create("prp") {
            storeFile = rootProject.file("signature/prp-essai.jks")
            storePassword = "prp-essai"
            keyAlias = "prp"
            keyPassword = "prp-essai"
        }
    }

    buildTypes {
        // « essai » : testOnly (voir src/debug), retirable par adb, débogage USB conservé.
        debug { signingConfig = signingConfigs.getByName("prp") }
        // version réelle : aucune porte de sortie avant l'échéance.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("prp")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    androidResources { noCompress += listOf("onnx") }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
}
