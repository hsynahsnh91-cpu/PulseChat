import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.pulsechat.kxmpzq"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "com.example.PulseTestRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
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
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
    // Fast build for trying the app on a phone: the same app as the debug build (same package, data
    // and debug signing key, so it installs over it) but not debuggable, which makes scrolling and
    // animations much smoother. Not for publishing; the release build above is unchanged.
    create("optimized") {
      initWith(getByName("debug"))
      isDebuggable = false
      isJniDebuggable = false
      matchingFallbacks += listOf("debug")
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
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  // Security: Gemini credentials must never be compiled into the APK. Gemini is reached through
  // Firebase AI Logic, which keeps the Gemini API key server-side.
  ignoreList.add("GEMINI_API_KEY")
  ignoreList.add("AI_API_KEY")
}

// Export Room schemas so every database version change is reviewed and migration-tested.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Builds outside AI Studio (Android Studio, command line): signingConfigs.debugConfig above expects
// ${rootDir}/debug.keystore, which AI Studio provides but Git does not contain (keystores are
// git-ignored). When it is missing, create one with the standard Android debug credentials right
// before the debug signing check, so a fresh clone builds and runs without manual steps.
// No effect when the file already exists (AI Studio, CI); release signing is not involved.
val createDebugKeystore = tasks.register<Exec>("createDebugKeystore") {
  group = "build setup"
  description = "Creates the debug keystore expected by signingConfigs.debugConfig if it is missing."
  val keystore = rootProject.file("debug.keystore")
  val javaHome = File(System.getProperty("java.home"))
  val keytool = listOf("bin/keytool.exe", "bin/keytool").map { File(javaHome, it) }.firstOrNull { it.isFile }?.absolutePath ?: "keytool"
  onlyIf("the debug keystore is missing") { !keystore.exists() }
  commandLine(
    keytool, "-genkeypair", "-keystore", keystore.absolutePath, "-storepass", "android",
    "-alias", "androiddebugkey", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
    "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US",
  )
}
tasks.named { it.startsWith("validateSigningDebug") || it.startsWith("validateSigningOptimized") }.configureEach { dependsOn(createDebugKeystore) }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.firebase.appcheck.playintegrity)
  // Push notifications, media storage, app foreground tracking and WebRTC calls.
  implementation(libs.firebase.messaging)
  implementation(libs.firebase.storage)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.stream.webrtc.android)
  // Scheduled messages (deferrable work that survives restarts and waits for a network).
  implementation(libs.androidx.work.runtime.ktx)
  // On-device translation (message text never leaves the phone; language packs download on demand).
  implementation(libs.mlkit.translate)
  implementation(libs.mlkit.language.id)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
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
  constraints {
    // A transitive dependency pulls a pre-1.3 Fragment, which mishandles ActivityResult request
    // codes (lint: InvalidFragmentVersionForActivityResult). Upgrade it; no new library is added.
    implementation("androidx.fragment:fragment:1.8.5")
  }
}
