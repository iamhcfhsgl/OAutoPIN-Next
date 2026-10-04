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

# The module entry point is loaded by name: META-INF/xposed/java_init.list holds
# "io.github.achyuki.oautopin.Hook" and the framework instantiates that class
# reflectively in the hooked process.
#
# The class name must therefore be preserved. Do NOT add allowobfuscation here:
# minification would rename the class to something like `d0.c`, the entry class
# would no longer exist under the declared name, and the release build would
# silently stop working. -adaptresourcefilecontents is not a substitute, because
# the framework reads the literal name from the list file.
-keep,allowoptimization,allowshrinking public class io.github.achyuki.oautopin.Hook {
    public <init>();
}

# Safety net for any additional libxposed entry class added later: they all
# inherit from XposedModule.
-keep,allowoptimization,allowshrinking public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# libxposed annotations are compile-time only and are not shipped in the APK.
-dontwarn io.github.libxposed.annotation.**
