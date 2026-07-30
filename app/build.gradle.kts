import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Cue ABA endpoint configuration for the `cueAba` flavor. Deliberately NOT
// committed (see .gitignore) — copy cue.properties.example and fill it in. An
// absent file is fine: the fields default to empty and the integration reports
// itself unconfigured instead of failing the build.
val cueProperties = Properties().apply {
    file("cue.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun cueProperty(key: String): String = (cueProperties[key] as String?).orEmpty()

android {
    namespace = "com.fran.teclas"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fran.teclas"
        minSdk = 31
        targetSdk = 37
        versionCode = 42
        versionName = "0.5.3"

        // Personal sideload app: every target device (Honor fold, Pixel, Apple-Silicon emulator)
        // is arm64-v8a. Shipping the other three ABIs tripled the APK's native-lib payload.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                // llama.cpp release builds even in app-debug: -O0 inference is unusably slow.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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

    // ── Distribution flavors ──────────────────────────────────────────────────
    // `consumer` is the launcher as it has always been. `cueAba` adds the private
    // Cue ABA record search. The split is a source set, not a runtime flag, so a
    // consumer APK contains no Cue code, no endpoints and no strings — R8 has
    // nothing to strip because nothing was compiled in.
    //
    // Both flavors share `applicationId`, deliberately: this is a personal daily
    // driver, and a suffix would install a second launcher with none of the
    // existing prefs, themes or trained prediction data. Add
    // `applicationIdSuffix = ".aba"` to cueAba if you ever want them side by side.
    flavorDimensions += "distribution"
    productFlavors {
        create("cueAba") {
            dimension = "distribution"
            versionNameSuffix = "-aba"
            buildConfigField("boolean", "CUE_ABA", "true")
            buildConfigField("String", "CUE_API_BASE_URL", "\"${cueProperty("cue.api.base.url").ifEmpty { "https://app.cueaba.com" }}\"")
            buildConfigField("String", "CUE_SUPABASE_URL", "\"${cueProperty("supabase.url")}\"")
            buildConfigField("String", "CUE_SUPABASE_ANON_KEY", "\"${cueProperty("supabase.anon.key")}\"")
        }
        create("consumer") {
            dimension = "distribution"
            buildConfigField("boolean", "CUE_ABA", "false")
            buildConfigField("String", "CUE_API_BASE_URL", "\"\"")
            buildConfigField("String", "CUE_SUPABASE_URL", "\"\"")
            buildConfigField("String", "CUE_SUPABASE_ANON_KEY", "\"\"")
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
        // Flavors gate the Cue integration through BuildConfig.CUE_ABA.
        buildConfig = true
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
    // Automatic heap-leak detection in debug builds only — zero release impact.
    debugImplementation(libs.leakcanary)
    // Installs bundled baseline profiles (Compose ships them) for AOT-compiled hot paths;
    // also enables Play cloud profiles after a store release.
    implementation(libs.profileinstaller)
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
    // General-purpose on-device generation (Gemini Nano Prompt API) — the nano-first path in
    // GeminiClient. Cloud Gemini becomes the fallback for devices without AICore.
    implementation(libs.mlkit.genai.prompt)
    // Keyboard-safe on-device rewriting: AICore blocks the raw Prompt API when the IME types into
    // another app (ErrorCode 30), but the task-specific Rewriting API is built for keyboards.
    implementation(libs.mlkit.genai.rewriting)
    // Depth wallpaper: on-device foreground-subject extraction (iOS-style depth effect). Runs once
    // per wallpaper at set-time to produce a cached transparent cutout — no runtime cost after.
    // Module downloads on demand via Play services; absent devices fall back to the flat wallpaper.
    implementation(libs.mlkit.subject.segmentation)
    // S Pen handwriting → text for launcher search. On-device ML Kit Digital Ink Recognition;
    // the recognition model downloads on first use and runs offline. Only engaged by stylus input.
    implementation(libs.mlkit.digital.ink)
    // Semantic search now runs on the app's own llama.cpp (nomic-embed GGUF, EmbedEngine) —
    // no AI Edge RAG SDK, no license-gated model, no tokenizer file.
    // Shizuku: adb/root-privileged binder access so the launcher can pin apps into the docked
    // top region (setTaskWindowingMode + resizeTask) — freeform launchBounds alone don't survive
    // in-app navigation and are blocked outright on some OEMs. HiddenApiBypass reaches the hidden
    // IActivityTaskManager/RunningTaskInfo APIs from the app process.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)

    // Google's official emoji picker widget (categories, search, recents, skin tones) — used for the
    // emoji button when typing into a third-party app.
    implementation("androidx.emoji2:emoji2-emojipicker:1.4.0")

    // Unit tests for the shared keyboard core (pure-JVM logic: word placement, prediction/autocorrect).
    testImplementation(libs.junit)
    // Robolectric: the typing-accuracy gate drives the real TapResolver/SpatialScorer, which use
    // android.graphics.Rect — real implementations on the JVM instead of throwing stubs.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
