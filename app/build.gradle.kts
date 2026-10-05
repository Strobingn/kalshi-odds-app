plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.paparazzi")
}

// Single versionCode offset. AppUpdater reads BuildConfig.VERSION_CODE_BASE.
// .github/workflows/build-apk.yml reads this same literal.
val versionCodeBase = 1_100_000

android {
    namespace = "com.dirk.kalshiodds"
    compileSdk = 35

    defaultConfig {
        // Independent install from the original DipHunter app. Keep this ID stable.
        applicationId = "com.dirk.kalshiodds.chatgtp"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions run numbers increase with each branch push, so a
        // new APK updates this separate installation without version downgrades.
        versionCode = versionCodeBase + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.3.4"
        val updateBranch = System.getenv("GITHUB_REF_NAME")?.takeIf { it.isNotBlank() } ?: "grokbot"
        val safeBranch = updateBranch.replace("\\", "").replace("\"", "").replace(" ", "")
        val updateReleaseTag = System.getenv("UPDATE_RELEASE_TAG")?.takeIf { it.isNotBlank() }
            ?: "$safeBranch-latest"
        buildConfigField("int", "VERSION_CODE_BASE", versionCodeBase.toString())
        buildConfigField(
            "String",
            "UPDATE_RELEASE_TAG",
            "\"${updateReleaseTag.replace("\\", "").replace("\"", "")}\""
        )
        buildConfigField(
            "String",
            "UPDATE_ASSET_NAME",
            "\"DipHunter-$safeBranch.apk\""
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStorePath = (findProperty("RELEASE_STORE_FILE") as String?)
        ?: System.getenv("RELEASE_STORE_FILE")
    val releaseStorePassword = (findProperty("RELEASE_KEYSTORE_PASSWORD") as String?)
        ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyAlias = (findProperty("RELEASE_KEY_ALIAS") as String?)
        ?: System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = (findProperty("RELEASE_KEY_PASSWORD") as String?)
        ?: System.getenv("RELEASE_KEY_PASSWORD")
    val releaseStoreFile = releaseStorePath?.takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.isFile }
    val hasReleaseSigning = releaseStoreFile != null &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile!!
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Standard Android debug keystore (~/.android/debug.keystore).
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
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

    androidResources {
        noCompress += "tflite"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

base {
    archivesName.set("DipHunter")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device ML
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // Persistence + encrypted API-key storage (never commit secrets)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.0.0")

    // Kalshi WS signing: RSA-PSS + Ed25519 PEM
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android stubs JSONObject by default).
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

