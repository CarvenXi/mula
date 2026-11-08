# mula proguard rules
-dontwarn org.jetbrains.compose.**
-keep class dev.dash.mula.** { *; }

# ---- 内核插件（Servo / GeckoView）的父类加载器依赖 ----
# 插件 APK 用 DexClassLoader 加载，父加载器是主应用。Servo 的 AAR 是 files() 依赖，
# 不带传递依赖，运行时直接引用主应用里的 Compose runtime / 协程 / Kotlin 标准库（按原始类名）。
# 主应用开了 R8：这些类会被改名或当成没用到而删掉，插件就会 NoClassDefFoundError
# （如 androidx.compose.runtime.SnapshotStateKt）。所以这里必须按原名保留。
-keep class androidx.compose.runtime.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.annotation.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
