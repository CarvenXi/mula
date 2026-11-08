# 宿主通过反射加载 Servo 的 Java 层，整体保留
-keep class org.servo.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-dontwarn org.servo.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
