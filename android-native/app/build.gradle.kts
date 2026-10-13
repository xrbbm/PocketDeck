import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Frozen Flutter baseline uses compile/target 36, min 24 (FlutterExtension).
    // Native raises min to 26 for CameraX + DataStore desugaring headroom.
    namespace = "com.xrbbm.pocketdeck"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xrbbm.pocketdeck"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.9.0-n1"
    }

    // Release signing reuses the Flutter keystore (android/key.properties),
    // debug key otherwise (mirrors android/app/build.gradle.kts).
    // storeFile (e.g. keystore/x.jks) resolves from android/ in Flutter;
    // anchor the same file from android-native via ../android/<rel>.
    val keystoreProperties = Properties()
    val keystorePropsFile = rootProject.file("../android/key.properties")
    if (keystorePropsFile.exists()) {
        keystoreProperties.load(keystorePropsFile.inputStream())
    }
    // NOTE: android/key.properties storeFile=../keystore/x.jks is stale
    // (the file actually lives at android/keystore/): try the literal path
    // first, then the ../-stripped form.
    val androidDir = rootProject.projectDir.toPath().resolve("../android").normalize()
    fun androidFile(rel: String): java.io.File {
        val norm = rel.replace('\\', '/')
        val direct = androidDir.resolve(norm).normalize().toFile()
        if (direct.exists()) return direct
        val stripped = norm.removePrefix("../")
        return androidDir.resolve(stripped).normalize().toFile()
    }
    val hasReleaseKey =
        (keystoreProperties["keyAlias"] as String?).isNullOrBlank().not() &&
            (keystoreProperties["keyPassword"] as String?).isNullOrBlank().not() &&
            (keystoreProperties["storePassword"] as String?).isNullOrBlank().not() &&
            (keystoreProperties["storeFile"] as String?).isNullOrBlank().not() &&
            (keystoreProperties["storeFile"] as String?)?.let { androidFile(it).exists() } == true
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = androidFile(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val bom = libs.androidx.compose.bom
    implementation(platform(bom))
    androidTestImplementation(platform(bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commonmark)

    testImplementation(libs.junit)
}
