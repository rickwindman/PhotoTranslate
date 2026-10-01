# Keep JNI native method names
-keepclasseswithmembernames class com.destinywind.dcim.core.ocr.OcrEngine { native <methods>; }
# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
