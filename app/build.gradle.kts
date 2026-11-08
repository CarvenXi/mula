import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Servo engine is delivered as a separately built APK plugin. The main app has no Servo classes.
// Default to the latest Servo plugin asset published with Mula GitHub Releases.
// A SHA-256 may be supplied at build time; otherwise the app fetches the adjacent .sha256 file.
val servoPluginUrl = providers.gradleProperty("servoPluginUrl")
    .orElse("https://github.com/CarvenXi/mula/releases/latest/download/servo-engine.apk")
val servoPluginSha256 = providers.gradleProperty("servoPluginSha256").orElse("")
val geckoPluginUrl = providers.gradleProperty("geckoPluginUrl")
    .orElse("https://github.com/CarvenXi/mula/releases/latest/download/gecko-engine.apk")
val geckoPluginSha256 = providers.gradleProperty("geckoPluginSha256").orElse("")

android {
    namespace = "dev.dash.mula"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.dash.mula"
        minSdk = 33
        targetSdk = 37
        versionCode = 151
        versionName = "v1.5.1"
        buildConfigField("String", "SERVO_PLUGIN_APK_URL", "\"${servoPluginUrl.get()}\"")
        buildConfigField("String", "SERVO_PLUGIN_SHA256", "\"${servoPluginSha256.get()}\"")
        buildConfigField("String", "GECKO_PLUGIN_APK_URL", "\"${geckoPluginUrl.get()}\"")
        buildConfigField("String", "GECKO_PLUGIN_SHA256", "\"${geckoPluginSha256.get()}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        val keystorePath = System.getenv("MULA_KEYSTORE")
        if (keystorePath != null && rootProject.file(keystorePath).exists()) {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = System.getenv("MULA_STORE_PASS")
                keyAlias = System.getenv("MULA_KEY_ALIAS")
                keyPassword = System.getenv("MULA_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}


dependencies {
    val miuix = "0.9.4"
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:$miuix")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.browser:browser:1.8.0")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
