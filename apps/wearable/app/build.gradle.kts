import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "ai.openclaw.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.openclaw.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Rolling build stamp shown on the dial's trailing "build" page, so we
        // can visually confirm the watch is running the current APK. Evaluated
        // at Gradle configuration time — rerun `:app:installDebug` (no config
        // cache) to refresh.
        val buildStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // SpriteCore Kotlin SDK — wire types, animation graph, sprite player, marker
    // parser. Pure-JVM core + Android variant for BitmapFrameSource. Hosted on
    // GitHub Packages; see settings.gradle.kts.
    implementation("ai.openclaw.spritecore:sprite-core-client:0.5.1")
    implementation("ai.openclaw.spritecore:sprite-core-client-android:0.5.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.02.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")

    // Wear OS Compose
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear.compose:compose-navigation:1.4.1")
    implementation("androidx.wear:wear:1.3.0")

    // Wearable Data Layer (phone bridge)
    implementation("com.google.android.gms:play-services-wearable:19.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Coil for loading static agent avatar images (PNG/WebP/JPG). Animated
    // GIFs are intentionally not supported — agents that need motion must
    // use the sprites or atlas format, rendered through the SpriteCore SDK.
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
