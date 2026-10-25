# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ---- TrainLog ----
# Request/response models are (de)serialized by kotlinx.serialization through
# Retrofit; keep their names and fields so JSON keys still match.
-keep class com.example.workoutlog_androidstudio.api.** { *; }
-keep @kotlinx.serialization.Serializable class com.example.workoutlog_androidstudio.** { *; }
-keepclassmembers class com.example.workoutlog_androidstudio.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit reads the API interface's annotations and generic types at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Line numbers in Crashlytics stack traces (names are still obfuscated;
# Crashlytics de-obfuscates them with the uploaded mapping file).
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Credential Manager (Google sign-in, saved passwords) finds its Play
# services provider by reflection.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
