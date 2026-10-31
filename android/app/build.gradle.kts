import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One version for iOS and Android: MARKETING_VERSION in the XcodeGen spec.
val marketingVersion: String = rootProject.file("../project.yml").readLines()
    .firstNotNullOfOrNull { Regex("""MARKETING_VERSION:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
    ?: "0.0.0"

// Release signing from CI secrets (or android/keystore.properties locally); without them the
// release build is signed with the debug key.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
    System.getenv("ANDROID_KEYSTORE_FILE")?.let { setProperty("storeFile", it) }
    System.getenv("ANDROID_KEYSTORE_PASSWORD")?.let { setProperty("storePassword", it) }
    System.getenv("ANDROID_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    System.getenv("ANDROID_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
}
val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !signing.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.nxnson.vesnavi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nxnson.vesnavi"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = marketingVersion
        // Phones are ARM. The x86 builds only serve emulators (and would double the APK), so they are
        // left out unless asked for, as the CI smoke test does with -Pvesnavi.allAbis=true.
        if (providers.gradleProperty("vesnavi.allAbis").orNull != "true") {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: drops unused code (most of the Material icon set) and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":navcore"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.maplibre.gl:android-sdk:11.5.2")
}
