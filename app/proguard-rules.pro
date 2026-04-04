-repackageclasses ''
-ignorewarnings
-dontwarn
-dontnote

-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

-keep class kotlin.Metadata { *; }