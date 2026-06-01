# Shrink only
-dontobfuscate

# AGP 8.x
-dontwarn com.google.protobuf.java_com_google_android_gmscore_sdk_target_granule__proguard_group_gtm_N1281923064GeneratedExtensionRegistryLite$Loader

# Remove verbose and debug logging
-assumenosideeffects class android.util.Log {
	public static boolean isLoggable(java.lang.String, int);
	public static int v(...);
}

# For generics reflection to work
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keep interface com.yzddmr6.prismspace.util.Hacks$* { *; }

# Keep source file and line number attributes for crash reports.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
