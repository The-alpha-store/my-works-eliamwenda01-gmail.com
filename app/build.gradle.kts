import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// ✅ ADD THIS BLOCK — one opt-in for the whole module.
// Kills every "This material API is experimental" warning, so you
// don't need @OptIn(ExperimentalMaterial3Api::class) anywhere.
// ---- Live-backend settings -------------------------------------------------------------------
// Each value is taken from the first place that has it:
//   1. a Gradle property            (~/.gradle/gradle.properties or -PSUPABASE_URL=...)
//   2. an environment variable      (GitHub Actions secrets)
//   3. the .env file in the project root (copy .env.example to .env)
val dotEnv: Map<String, String> = run {
  val file = rootProject.file(".env")
  if (!file.exists()) emptyMap() else file.readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
    .associate { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim().trim('"', '\'') }
}

fun backendSetting(name: String, default: String = ""): String =
  providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
    ?: dotEnv[name]?.takeIf { it.isNotBlank() }
    ?: default

// A release build must never ship without a real backend, and never in demo mode.
if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
  val url = backendSetting("SUPABASE_URL")
  val key = backendSetting("SUPABASE_ANON_KEY")
  if (!url.startsWith("https://") || url.contains("YOUR_PROJECT_REF", ignoreCase = true) ||
    key.isBlank() || key.contains("YOUR_SUPABASE", ignoreCase = true)
  ) {
    throw GradleException(
      "Release build stopped: SUPABASE_URL and SUPABASE_ANON_KEY are missing or still placeholders. " +
        "Set them in .env, Gradle properties or GitHub repository secrets (see GO_LIVE.md)."
    )
  }
  if (backendSetting("DEMO_MODE_ENABLED", "false").equals("true", ignoreCase = true)) {
    throw GradleException("Release build stopped: DEMO_MODE_ENABLED must be false in a release.")
  }
}


kotlin {
  compilerOptions {
    freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    // add more opt-ins here as needed, e.g.
    // freeCompilerArgs.add("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
    // freeCompilerArgs.add("-opt-in=androidx.compose.material.ExperimentalMaterialApi")
  }
}

android {
  namespace = "com.willykez.campconnect"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.willykez.campconnect"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    buildConfigField(
      "boolean",
      "DEMO_MODE_ENABLED",
      if (backendSetting("DEMO_MODE_ENABLED", "false").equals("true", ignoreCase = true)) "true" else "false"
    )

    val supabaseUrl = backendSetting("SUPABASE_URL")
    val supabaseAnonKey = backendSetting("SUPABASE_ANON_KEY")

    buildConfigField(
      "String",
      "SUPABASE_URL",
      "\"${supabaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    )

    buildConfigField(
      "String",
      "SUPABASE_ANON_KEY",
      "\"${supabaseAnonKey.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    )

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("STORE_PASSWORD")
    val releaseKeyPassword = System.getenv("KEY_PASSWORD")

    if (!releaseKeystorePath.isNullOrBlank() &&
      !releaseStorePassword.isNullOrBlank() &&
      !releaseKeyPassword.isNullOrBlank()
    ) {
      create("release") {
        storeFile = file(releaseKeystorePath)
        storePassword = releaseStorePassword
        keyAlias = "upload"
        keyPassword = releaseKeyPassword
      }
    }

    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )

      if (signingConfigs.findByName("release") != null) {
        signingConfig = signingConfigs.getByName("release")
      }
    }

    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  // These three are read by backendSetting() above, so the plugin must not generate them twice.
  ignoreList.add("SUPABASE_URL")
  ignoreList.add("SUPABASE_ANON_KEY")
  ignoreList.add("DEMO_MODE_ENABLED")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.mlkit.text.recognition)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)

  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}