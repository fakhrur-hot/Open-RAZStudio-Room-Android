# Add project specific ProGuard rules here.

# ── Logging: strip ALL log output in release builds ──────────────────────────
# AppLogger.isEnabled is false in release (BuildConfig.DEBUG == false).
# R8 constant-folds the if(!AppLogger.isEnabled) return guards and eliminates
# the entire method body. These assumenosideeffects rules are a belt-and-
# suspenders backstop: any android.util.Log call that somehow survives the
# AppLog/AppLogger gate (third-party libs, reflection paths) is also stripped.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
    public static int println(int, java.lang.String, java.lang.String);
}
# AppLog wrapper — strip the whole class since all methods are no-ops in release.
-assumenosideeffects class com.RAZStudio.StudioRoom.core.utils.AppLog {
    public static void v(...);
    public static void d(...);
    public static void i(...);
    public static void w(...);
    public static void e(...);
    public static void wtf(...);
}


# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
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
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

-keep class * implements com.RAZStudio.StudioRoom.core.filters.presentation.model.UiFilter
-keepclassmembers class * implements com.RAZStudio.StudioRoom.core.filters.presentation.model.UiFilter {
    <init>(...);
}
-keep class * implements com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
-keepclassmembers class * implements com.RAZStudio.StudioRoom.core.filters.domain.model.Filter {
    <init>(...);
}
-keepclassmembers class com.RAZStudio.StudioRoom.core.filters.** {
    <init>(...);
}
-keep class com.RAZStudio.StudioRoom.core.filters.**
-keep class com.RAZStudio.StudioRoom.core.filters.*

# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

-keep class org.beyka.tiffbitmapfactory.**{ *; }

-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern

-keepclassmembers class * {
    public java.lang.String name();
}

-keep enum * { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * extends java.lang.Enum {
    public java.lang.String name();
}

-keep class ai.onnxruntime.** { *; }

-keep class com.google.firebase.crashlytics.** { *; }
-keep class com.google.firebase.analytics.** { *; }
-keep class androidx.pdf.** { *; }
-keepnames class androidx.pdf.** { *; }

# Moshi reflective adapters need generic signatures in release.
-keepattributes Signature
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

-keep class com.RAZStudio.StudioRoom.feature.markup_layers.data.project.** { *; }
-keep class  com.RAZStudio.StudioRoom.feature.markup_layers.data.project.**
-keep class  com.RAZStudio.StudioRoom.feature.markup_layers.data.project.*

# ──────────────────────────────────────────────────────────────────────────────
# Strip ALL android.util.Log.* calls in the release build.
#
# `-assumenosideeffects` tells R8 that these methods produce no observable
# effect and may be deleted along with the code that builds their arguments.
# This wipes the entire log call site — including any expensive string
# concatenation passed as arg — from the final dex. Crash reporting via the
# core/crash module is unaffected (it uses Thread.setDefaultUncaughtExceptionHandler,
# not Log.*).
#
# We include v/d/i/w/e/wtf and all overloads (with-Throwable variants too).
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** wtf(...);
    public static *** println(...);
    public static *** isLoggable(...);
}
# Note: Log.i/w/e intentionally NOT stripped — too useful for diagnostics
# of CanonSync runtime issues. Re-add to strip-list once Canon pipeline
# stabilises.

# Also catch Timber-style wrappers if any 3rd-party logging shows up later
# (no-op when those classes aren't on the classpath — `-dontwarn` keeps R8 quiet).
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-dontwarn timber.log.Timber

# coil-resvg uses JNA/Uniffi generated names at runtime.
-keep class com.hashsequence.coilresvg.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window

-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult

# MediaPipe Tasks-Vision pulls in AutoValue which references javax.lang.model.*
# (annotation-processor-only classes, not present at runtime). Safe to ignore.
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier