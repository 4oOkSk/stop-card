import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// A release has an explicit signing identity. Without it, local builds stay observation-only.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val appVersionName = "0.2.0"
val moduleSigner = if (keystoreProperties.isEmpty()) "" else {
    val store = KeyStore.getInstance(KeyStore.getDefaultType())
    rootProject.file(keystoreProperties.getProperty("storeFile")).inputStream().use {
        store.load(it, keystoreProperties.getProperty("storePassword").toCharArray())
    }
    val certificate = store.getCertificate(keystoreProperties.getProperty("keyAlias"))
        ?: error("Release certificate is missing")
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
}

base {
    archivesName = "stop-card-$appVersionName"
}

android {
    namespace = "io.github.user4ooksk.stopcard"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.user4ooksk.stopcard"
        minSdk = 28
        // Built against the Android 17 platform (API 37.0) from the SDK canary channel.
        // The first device target is Android 17; targetSdk stays 36 until behavior changes are checked.
        targetSdk = 36
        versionCode = 2
        versionName = appVersionName
        buildConfigField("String", "MODULE_SIGNER_SHA256", "\"$moduleSigner\"")
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Keeps the APK free of Google-signed dependency metadata (required for F-Droid style builds).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    compileOnly(libs.xposed.api)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
