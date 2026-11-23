// One version for all of Studio: the one in rpctool/__init__.py (the release
// workflow checks the tag against it).
val studioVersion: String = Regex("__version__ = \"([^\"]+)\"")
    .find(rootDir.parentFile.resolve("rpctool/__init__.py").readText())!!.groupValues[1]
val studioVersionCode: Int = studioVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.novalabs.rpcstudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.novalabs.rpcstudio"
        minSdk = 26              // java.util.Base64, which the link client uses
        targetSdk = 35
        versionCode = studioVersionCode
        versionName = studioVersion
    }

    // A release build is signed only when CI hands it a key (see the release
    // workflow); a local build is a debug build.
    signingConfigs {
        create("release") {
            val ks = System.getenv("STUDIO_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("STUDIO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STUDIO_KEY_ALIAS")
                keyPassword = System.getenv("STUDIO_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("STUDIO_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":core"))
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
}
