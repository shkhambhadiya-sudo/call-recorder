import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionCode/versionName are injected by CI (GitHub Actions) so that every
// release has a unique, monotonically increasing code for the in-app updater.
// Locally they default to a dev build.
val ciVersionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
val ciVersionName = System.getenv("APP_VERSION_NAME") ?: "1.0-dev"

// Repo coordinates used by the in-app auto-updater to query GitHub Releases.
val repoOwner = System.getenv("REPO_OWNER") ?: "OWNER_PLACEHOLDER"
val repoName = System.getenv("REPO_NAME") ?: "REPO_PLACEHOLDER"

android {
    namespace = "com.sanket.callrecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sanket.callrecorder"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName

        buildConfigField("String", "REPO_OWNER", "\"$repoOwner\"")
        buildConfigField("String", "REPO_NAME", "\"$repoName\"")

        // Read-only GitHub token for the in-app updater (private repo). Injected
        // by CI from the UPDATE_TOKEN secret; empty for local/dev builds.
        val updateToken = System.getenv("UPDATE_TOKEN") ?: ""
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
    }

    // Stable signing key committed by CI (see .github/workflows/build.yml).
    // This guarantees every build shares one signature so updates install over
    // the previous version. Fine for a personal app (not a Play Store upload).
    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore/release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "callrecorder"
                keyAlias = System.getenv("KEY_ALIAS") ?: "callrecorder"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "callrecorder"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksFile = rootProject.file("keystore/release.jks")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
