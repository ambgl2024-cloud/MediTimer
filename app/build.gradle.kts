plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.meditimer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.meditimer"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "0.6.7"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("MEDITIMER_KEYSTORE_PATH")
            val storePasswordValue = System.getenv("MEDITIMER_STORE_PASSWORD")
            val keyAliasValue = System.getenv("MEDITIMER_KEY_ALIAS")
            val keyPasswordValue = System.getenv("MEDITIMER_KEY_PASSWORD")

            if (!keystorePath.isNullOrBlank()) storeFile = file(keystorePath)
            storePassword = storePasswordValue
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.core:core:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
