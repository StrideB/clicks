plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") version "2.0.21-1.0.25"
}

android {
    namespace = "com.fran.clicks"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fran.clicks"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Shared debug signing: a committed keystore so every build (any machine / any session) signs
    // identically. Without this, each machine's own ~/.android/debug.keystore produces a different
    // signature, so reinstalling a build from another session was rejected as a signature mismatch
    // and required a data-wiping uninstall. Standard debug credentials — safe to commit.
    signingConfigs {
        getByName("debug") {
            storeFile = file("clicks-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Generate lambdas/SAM conversions as classes instead of invokedynamic. Works around a
        // Kotlin JVM backend crash ("Exception during IR lowering") when codegen'ing some Compose
        // (Composer, Int) -> Unit lambdas in HomeWidgetStack.kt on a full build.
        freeCompilerArgs = freeCompilerArgs + listOf("-Xlambdas=class", "-Xsam-conversions=class")
    }

    buildFeatures {
        compose = true
    }
}

// Export Room's schema JSON so future version bumps can be diffed and migrated explicitly
// (paired with removing fallbackToDestructiveMigration in NgramDatabase). The generated
// app/schemas/ files should be committed and included in the migration test source set later.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.dynamicanimation:dynamicanimation-ktx:1.0.0-alpha03")
    implementation("com.android.billingclient:billing-ktx:7.1.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // On-device Gemini Nano (AICore) proofreading via ML Kit GenAI — free, offline, no model to train.
    // No-op on devices without AICore (the engine checks feature status and hides the UI).
    implementation("com.google.mlkit:genai-proofreading:1.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    // Unit tests for the shared keyboard core (pure-JVM logic: word placement, prediction/autocorrect).
    testImplementation("junit:junit:4.13.2")
}
