plugins { alias(libs.plugins.android.application) }

android {
    namespace = "io.github.user4ooksk.stopcard.fixtures"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.user4ooksk.stopcard.test"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    flavorDimensions += "role"
    productFlavors {
        for (role in listOf("a", "b", "c")) create(role) {
            dimension = "role"
            applicationIdSuffix = ".$role"
            manifestPlaceholders["probeLabel"] = "Stop Card Test ${role.uppercase()}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Fixtures expose deliberate cross-app test components. Ship only the clearly labelled debug APKs.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { it.enable = false }
