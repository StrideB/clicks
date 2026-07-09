import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.fran.teclas"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fran.teclas"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Personal sideload app: every target device (Honor fold, Pixel, Apple-Silicon emulator)
        // is arm64-v8a. Shipping the other three ABIs tripled the APK's native-lib payload.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Shared debug signing: a committed keystore so every build (any machine / any session) signs
    // identically. Without this, each machine's own ~/.android/debug.keystore produces a different
    // signature, so reinstalling a build from another session was rejected as a signature mismatch
    // and required a data-wiping uninstall. Standard debug credentials — safe to commit.
    signingConfigs {
        getByName("debug") {
            storeFile = file("teclas-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same shared debug key so release builds install over debug ones on any machine.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Pre-modernization findings are frozen in the baseline; lint fails only on NEW issues.
        baseline = file("lint-baseline.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Export Room's schema JSON so future version bumps can be diffed and migrated explicitly
// (paired with removing fallbackToDestructiveMigration in NgramDatabase). The generated
// app/schemas/ files should be committed and included in the migration test source set later.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.browser)
    implementation(libs.core.ktx)
    implementation(libs.autofill)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.window)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // Prediction engine at-rest encryption: EncryptedSharedPreferences for model weights,
    // Keystore-backed AES/GCM for transition-log rows. Nothing leaves the device.
    implementation(libs.security.crypto)
    implementation(libs.billing.ktx)
    implementation(libs.onnxruntime.android)
    // On-device Gemini Nano (AICore) proofreading via ML Kit GenAI — free, offline, no model to train.
    // No-op on devices without AICore (the engine checks feature status and hides the UI).
    implementation(libs.mlkit.genai.proofreading)

    // Unit tests for the shared keyboard core (pure-JVM logic: word placement, prediction/autocorrect).
    testImplementation(libs.junit)
}
