import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.openclaw.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.openclaw.glasses"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    // SpriteCore: GlassesProtocol constants (channel bytes, service/char UUIDs)
    // for the Brilliant Frame BLE wiring. We replaced the rest of the SDK
    // (transport, client) with local OcGlasses* classes — only the protocol
    // constants are still consumed.
    implementation("ai.openclaw.spritecore:sprite-core-client:0.5.10")
    implementation("ai.openclaw.spritecore:sprite-core-client-android:0.5.10")
    implementation("ai.openclaw.spritecore:sprite-core-client-compose:0.5.10")
    implementation("ai.openclaw.spritecore:sprite-core-client-glasses:0.5.10")

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.animation)
    // material3 isn't in the wearable libs catalog (watch uses wear-compose-material);
    // BOM resolves the version.
    implementation("androidx.compose.material3:material3")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
