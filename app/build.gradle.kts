plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.dirk.kalshiodds"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dirk.kalshiodds"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.3.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            val jks = file("signing/diphunter-debug.jks")
            if (jks.isFile) {
                storeFile = jks
                storePassword = (findProperty("DIPHUNTER_DEBUG_STORE_PASSWORD") as String?)
                    ?: "diphunter-debug"
                keyAlias = (findProperty("DIPHUNTER_DEBUG_KEY_ALIAS") as String?)
                    ?: "diphunter-debug"
                keyPassword = (findProperty("DIPHUNTER_DEBUG_KEY_PASSWORD") as String?)
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

    androidResources {
        noCompress += "tflite"
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
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

val expectedDebugCertSha256 =
    "64e2a43a6897c4556a36b82ea31dc89550c65e56b053658e1438bdf3c4cc4608"

tasks.register("verifyDebugCert") {
    dependsOn("assembleDebug")
    doLast {
        val apk = file("build/outputs/apk/debug/DipHunter-debug.apk")
        require(apk.isFile) { "missing $apk" }
        val apksigner = file("${System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")}/build-tools/35.0.0/apksigner")
        val bin = if (apksigner.isFile) apksigner else file("${System.getenv("ANDROID_HOME")}/build-tools/34.0.0/apksigner")
        val out = providers.exec {
            commandLine(bin.absolutePath, "verify", "--print-certs", apk.absolutePath)
        }.standardOutput.asText.get()
        val digest = Regex("SHA-256 digest: ([0-9a-f]+)").find(out)?.groupValues?.get(1)
            ?: error("no SHA-256 in apksigner output:\n$out")
        check(digest.equals(expectedDebugCertSha256, ignoreCase = true)) {
            "debug APK cert $digest != $expectedDebugCertSha256"
        }
    }
}

afterEvaluate {
    tasks.named("assembleDebug") {
        doLast {
            val apk = file("build/outputs/apk/debug/DipHunter-debug.apk")
            if (!apk.isFile) return@doLast
            val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return@doLast
            val apksigner = listOf("35.0.0", "34.0.0").map { file("$home/build-tools/$it/apksigner") }.firstOrNull { it.isFile }
                ?: return@doLast
            val proc = ProcessBuilder(apksigner.absolutePath, "verify", "--print-certs", apk.absolutePath)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val digest = Regex("SHA-256 digest: ([0-9a-f]+)").find(out)?.groupValues?.get(1)
            if (digest != null && !digest.equals(expectedDebugCertSha256, ignoreCase = true)) {
                throw GradleException("debug APK cert $digest != $expectedDebugCertSha256\n$out")
            }
        }
    }
}
