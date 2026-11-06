plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.switchflow.companion"
    // Google Play takes only apps that target Android 16 (API 36) since 31 August 2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "app.switchflow.companion"
        // MediaStore RELATIVE_PATH / IS_PENDING and scoped storage need Android 10.
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    flavorDimensions += "store"
    productFlavors {
        // Everything, including moving apps between phones. Ships with the PC installer and the website.
        create("full") {
            dimension = "store"
            buildConfigField("boolean", "APP_TRANSFER", "true")
            buildConfigField("boolean", "STORE_BILLING", "false")
        }
        // Google Play: Play forbids installing apps from outside Play, so no app transfer; free tier
        // plus a one-time "SwitchFlow Pro" purchase through Google Play Billing.
        create("play") {
            dimension = "store"
            applicationId = "app.switchflow.transfer"
            buildConfigField("boolean", "APP_TRANSFER", "false")
            buildConfigField("boolean", "STORE_BILLING", "true")
        }
    }

    signingConfigs {
        // Release signing comes from environment variables so no secret lives in the repo.
        create("release") {
            val store = System.getenv("SWITCHFLOW_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("SWITCHFLOW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SWITCHFLOW_KEY_ALIAS")
                keyPassword = System.getenv("SWITCHFLOW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("SWITCHFLOW_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    "playImplementation"("com.android.billingclient:billing-ktx:8.0.0")
    // Billing brings an old androidx.fragment; the activity result APIs need 1.3+ (lint: InvalidFragmentVersionForActivityResult).
    "playImplementation"("androidx.fragment:fragment-ktx:1.8.5")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
