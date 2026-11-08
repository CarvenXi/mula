# 宿主通过反射加载入口类和回调接口，必须保留原名与成员
-keep class dev.dash.mula.geckoplugin.** { *; }
-keep interface dev.dash.mula.geckoplugin.** { *; }

# GeckoView 大量 JNI / 反射（@WrapForJNI 等），整体保留，避免运行时 NoSuchMethodError
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.geckoview.** { *; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-dontwarn org.mozilla.**
-dontwarn kotlin.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
