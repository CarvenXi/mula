import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
}

val servoAarUrl = providers.gradleProperty("servoAarUrl")
    .orElse("https://github.com/servo/servo/releases/latest/download/servo-aarch64-android.aar")
val servoAarSha256 = providers.gradleProperty("servoAarSha256").orNull
val aarFile = layout.buildDirectory.file("generated/servo/servo.aar")

fun downloadWithRetry(sourceUrl: String, destination: File): URL {
    val maxAttempts = 5
    var lastError: Exception? = null

    repeat(maxAttempts) { attempt ->
        var connection: HttpURLConnection? = null
        try {
            destination.parentFile.mkdirs()
            destination.delete()

            connection = URL(sourceUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mula-Servo-Plugin-Build")
            connection.setRequestProperty("Accept", "*/*")

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("HTTP $status while downloading $sourceUrl")
            }

            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            require(destination.isFile && destination.length() > 0L) {
                "Downloaded file is empty: $sourceUrl"
            }
            return connection.url
        } catch (error: Exception) {
            lastError = error
            destination.delete()
            if (attempt < maxAttempts - 1) {
                val delayMs = (5_000L shl attempt).coerceAtMost(30_000L)
                logger.warn(
                    "Servo download attempt ${attempt + 1}/$maxAttempts failed: ${error.message}. " +
                        "Retrying in ${delayMs / 1000}s."
                )
                Thread.sleep(delayMs)
            }
        } finally {
            connection?.disconnect()
        }
    }

    throw GradleException("Failed to download $sourceUrl after $maxAttempts attempts", lastError)
}


/**
 * Resolve GitHub's /releases/latest/download redirect without following the
 * second redirect to the temporary release-assets CDN URL. The first
 * Location contains the immutable tag-specific URL used for both AAR and
 * checksum downloads.
 */
fun resolveLatestReleaseAsset(sourceUrl: String): String {
    val maxAttempts = 5
    var lastError: Exception? = null

    repeat(maxAttempts) { attempt ->
        var connection: HttpURLConnection? = null
        try {
            connection = URL(sourceUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mula-Servo-Plugin-Build")
            connection.setRequestProperty("Range", "bytes=0-0")

            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("GitHub redirect did not include a Location header")
                val resolved = URL(URL(sourceUrl), location)
                require(resolved.host == "github.com" &&
                    resolved.path.contains("/releases/download/")) {
                    "Unexpected GitHub latest-release redirect: $resolved"
                }
                return resolved.toString()
            }
            if (status in 200..299) {
                // Some proxies may serve the asset directly instead of redirecting.
                return sourceUrl
            }
            throw IOException("HTTP $status while resolving latest Servo Release")
        } catch (error: Exception) {
            lastError = error
            if (attempt < maxAttempts - 1) {
                val delayMs = (5_000L shl attempt).coerceAtMost(30_000L)
                logger.warn(
                    "Resolving latest Servo Release failed (${attempt + 1}/$maxAttempts): " +
                        "${error.message}. Retrying in ${delayMs / 1000}s."
                )
                Thread.sleep(delayMs)
            }
        } finally {
            connection?.disconnect()
        }
    }

    throw GradleException("Could not resolve Servo's latest GitHub Release", lastError)
}

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val prepareServoAar by tasks.registering {
    outputs.file(aarFile)
    // Resolve the latest Release on every plugin build; never silently reuse an old AAR.
    outputs.upToDateWhen { false }

    doLast {
        val output = aarFile.get().asFile
        output.parentFile.mkdirs()
        val aarPart = File(output.parentFile, "servo.aar.part")
        val checksumPart = File(output.parentFile, "servo.aar.sha256.part")

        try {
            logger.lifecycle("Resolving latest Servo GitHub Release...")
            val resolvedAarUrl = resolveLatestReleaseAsset(servoAarUrl.get())
            logger.lifecycle("Downloading latest Servo Android AAR: $resolvedAarUrl")
            downloadWithRetry(resolvedAarUrl, aarPart)

            require(aarPart.length() > 1_000_000L) {
                "Servo AAR size looks invalid: ${aarPart.length()} bytes"
            }
            ZipFile(aarPart).use { aar ->
                require(aar.getEntry("classes.jar") != null) {
                    "Downloaded file is not a valid Android AAR (classes.jar missing)"
                }
            }

            val expectedSha256 = if (!servoAarSha256.isNullOrBlank()) {
                servoAarSha256.trim().lowercase()
            } else {
                val checksumUrl = resolvedAarUrl + ".sha256"
                logger.lifecycle("Fetching Servo checksum for the resolved Release...")
                downloadWithRetry(checksumUrl, checksumPart)
                Regex("(?i)\\b[0-9a-f]{64}\\b")
                    .find(checksumPart.readText(Charsets.UTF_8))
                    ?.value
                    ?.lowercase()
                    ?: throw GradleException("Could not parse SHA-256 from $checksumUrl")
            }

            require(Regex("[0-9a-f]{64}").matches(expectedSha256)) {
                "servoAarSha256 must contain exactly 64 hexadecimal characters"
            }
            val actualSha256 = sha256Of(aarPart)
            require(actualSha256 == expectedSha256) {
                "Servo AAR SHA-256 mismatch. Expected $expectedSha256, got $actualSha256"
            }

            Files.move(
                aarPart.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            logger.lifecycle("Servo latest AAR ready: ${output.length()} bytes, SHA-256 $actualSha256")
        } finally {
            aarPart.delete()
            checksumPart.delete()
        }
    }
}

android {
    namespace = "dev.dash.mula.servoplugin"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.dash.mula.servoplugin"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Servo 官方 AAR 只有 aarch64
        ndk { abiFilters += "arm64-v8a" }
    }

    // 签名：同 gecko-engine-plugin；没有 keystore 时回退 debug 签名，绝不输出未签名 APK
    signingConfigs {
        val keystorePath = System.getenv("MULA_KEYSTORE")
        if (!keystorePath.isNullOrBlank() && rootProject.file(keystorePath).isFile) {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = System.getenv("MULA_STORE_PASS")
                keyAlias = System.getenv("MULA_KEY_ALIAS")
                keyPassword = System.getenv("MULA_KEY_PASS")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        jniLibs {
            // 宿主自行解压原生库，so 压缩存放以减小体积
            useLegacyPackaging = true
            excludes += setOf("lib/armeabi-v7a/**", "lib/x86/**", "lib/x86_64/**")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/DebugProbesKt.bin",
                "/kotlin-tooling-metadata.json",
            )
        }
    }
}

dependencies {
    implementation(files(aarFile))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

tasks.configureEach {
    if (name == "preBuild") dependsOn(prepareServoAar)
}
