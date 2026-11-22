plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.codenong.xiaozhi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codenong.xiaozhi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.15.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("XIAOZHI_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("XIAOZHI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XIAOZHI_KEY_ALIAS")
                keyPassword = System.getenv("XIAOZHI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        val keystorePath = System.getenv("XIAOZHI_KEYSTORE_FILE")
        val sharedSigning = if (!keystorePath.isNullOrBlank()) {
            signingConfigs.getByName("release")
        } else {
            null
        }
        debug {
            if (sharedSigning != null) {
                signingConfig = sharedSigning
            }
        }
        release {
            isMinifyEnabled = false
            if (sharedSigning != null) {
                signingConfig = sharedSigning
            }
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
        compose = true
    }
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.2.aar"))
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
}
