import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
}

val servoAarUrl = providers.gradleProperty("servoAarUrl")
    .orElse("https://github.com/servo/servo/releases/latest/download/servo-aarch64-android.aar")
val servoAarSha256 = providers.gradleProperty("servoAarSha256").orNull
val aarFile = layout.buildDirectory.file("generated/servo/servo.aar")
val prepareServoAar by tasks.registering {
    outputs.file(aarFile)
    outputs.upToDateWhen { aarFile.get().asFile.isFile }
    doLast {
        val out = aarFile.get().asFile; out.parentFile.mkdirs()
        val tmp = File(out.parentFile, "servo.aar.part")
        URL(servoAarUrl.get()).openConnection().apply {
            connectTimeout = 30000; readTimeout = 180000
            setRequestProperty("User-Agent", "Mula-Servo-Plugin-Build")
        }.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        require(tmp.length() > 1_000_000L) { "Servo AAR size looks invalid" }
        ZipFile(tmp).use { require(it.getEntry("classes.jar") != null) { "Not a valid AAR" } }
        if (!servoAarSha256.isNullOrBlank()) {
            val actual = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()).joinToString("") { "%02x".format(it) }
            require(actual.equals(servoAarSha256, true)) { "Servo AAR checksum mismatch" }
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

android {
    namespace = "dev.dash.mula.servoplugin"
    compileSdk = 37
    defaultConfig { applicationId = "dev.dash.mula.servoplugin"; minSdk = 33; targetSdk = 36; versionCode = 1; versionName = "1.0.0" }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { jniLibs { useLegacyPackaging = false }; resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(files(aarFile))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

tasks.configureEach { if (name == "preBuild") dependsOn(prepareServoAar) }
