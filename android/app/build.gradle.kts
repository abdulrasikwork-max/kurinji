import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The backend URL comes from local.properties (never committed), so it can change without code edits.
// Default: http://10.0.2.2:8080/. Inside the Android emulator, "localhost" means the emulator itself,
// while 10.0.2.2 is the special alias for the host computer (where Spring Boot runs).
// A real phone needs your computer's LAN address instead, for example http://192.168.1.20:8080/
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val apiBaseUrl: String = localProperties.getProperty("api.base.url")?.takeIf { it.isNotBlank() }
    ?: "http://10.0.2.2:8080/"
val evidenceVaultKeyId: String = localProperties.getProperty("evidence.vault.key.id")?.trim()
    ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) } ?: ""

android {
    namespace = "com.safesphere.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.safesphere.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase1"

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Phase 5: optional pin of the evidence vault recovery key (its SHA-256 key id, printed by VaultKeyTool).
        // When set, the app refuses any other escrow key the server offers. Empty = trust the server's key.
        buildConfigField("String", "EVIDENCE_VAULT_KEY_ID", "\"$evidenceVaultKeyId\"")
    }

    buildTypes {
        release {
            // Shrinking is left off for Phase 1; enable it before any public release.
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.google.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)

    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)

    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
