import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
    // parser. Pure-JVM core + Android variant for BitmapFrameSource + Compose
    // wrapper that ships the avatar Composable. Hosted on GitHub Packages;
    // see settings.gradle.kts.
    implementation("ai.openclaw.spritecore:sprite-core-client:0.5.10")
    implementation("ai.openclaw.spritecore:sprite-core-client-android:0.5.10")
    implementation("ai.openclaw.spritecore:sprite-core-client-compose:0.5.10")

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.animation)

    // Wear OS Compose
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear)

    // Wearable Data Layer (phone bridge)
    implementation(libs.play.services.wearable)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // Coil for loading static agent avatar images (PNG/WebP/JPG). Animated
    // GIFs are intentionally not supported — agents that need motion must
    // use the sprites or atlas format, rendered through the SpriteCore SDK.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
