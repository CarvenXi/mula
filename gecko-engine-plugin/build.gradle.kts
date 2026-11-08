plugins {
    id("com.android.application")
}

val geckoViewVersion = providers.gradleProperty("geckoViewVersion")
    .orElse("157.0.20260910085851")

android {
    namespace = "dev.dash.mula.geckoplugin"

    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "dev.dash.mula.geckoplugin"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = geckoViewVersion.get()

        // 只保留 arm64-v8a：GeckoView AAR 自带 armeabi-v7a / x86 / x86_64 的 libxul 等，体积翻几倍
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 签名：CI 里用与主应用同一套 keystore（MULA_KEYSTORE / MULA_STORE_PASS / MULA_KEY_ALIAS / MULA_KEY_PASS）。
    // 没提供时回退到 debug 签名——绝不输出未签名 APK（未签名会报 INSTALL_PARSE_FAILED_NO_CERTIFICATES）。
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
            // 插件只有一个入口类，其余是 GeckoView：R8 去掉没用到的 Java 代码；GeckoView / 入口类由 proguard-rules.pro 保留
            isMinifyEnabled = true
            // 资源极少，且 GeckoView 部分资源靠名称反射获取，不做资源裁剪以免误删
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            // 宿主自己从 APK 里解压原生库再加载，不依赖系统直接 mmap 未压缩的 so，
            // 所以让 so 以 deflate 压缩存放：libxul 等大文件体积大幅缩小
            useLegacyPackaging = true
            excludes += setOf("lib/armeabi-v7a/**", "lib/x86/**", "lib/x86_64/**")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/META-INF/com/android/build/gradle/**",
                "/DebugProbesKt.bin",
                "/kotlin-tooling-metadata.json",
            )
        }
    }
}

dependencies {
    implementation(
        "org.mozilla.geckoview:geckoview-nightly:${geckoViewVersion.get()}"
    )
}