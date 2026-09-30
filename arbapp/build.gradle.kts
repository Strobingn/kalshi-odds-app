plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Arb Hunter: read-only scanner for structural (risk-free) Kalshi arbitrage.
// Public market data only. Never places orders, never asks for an API key.
android {
    namespace = "com.dirk.kalshiarb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dirk.kalshiarb"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Reuse Dip Hunter's committed debug sideload keystore so CI APKs
            // install over each other. Not a release cert.
            val jks = file("../app/signing/diphunter-debug.jks")
            if (jks.isFile) {
                storeFile = jks
                storePassword = (findProperty("DIPHUNTER_DEBUG_STORE_PASSWORD") as String?)
                    ?: System.getenv("DIPHUNTER_DEBUG_STORE_PASSWORD")
                    ?: "diphunter-debug"
                keyAlias = (findProperty("DIPHUNTER_DEBUG_KEY_ALIAS") as String?)
                    ?: System.getenv("DIPHUNTER_DEBUG_KEY_ALIAS")
                    ?: "diphunter-debug"
                keyPassword = (findProperty("DIPHUNTER_DEBUG_KEY_PASSWORD") as String?)
                    ?: System.getenv("DIPHUNTER_DEBUG_KEY_PASSWORD")
                    ?: "diphunter-debug"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            pickFirsts += "META-INF/LICENSE"
            pickFirsts += "META-INF/LICENSE.md"
            pickFirsts += "META-INF/NOTICE"
        }
    }
}

base {
    archivesName.set("ArbHunter")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Networking: public, unauthenticated GETs only.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
