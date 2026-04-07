-repackageclasses ''
-ignorewarnings
-dontwarn
-dontnote

-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

-keep class com.a.labs.data.remote.dto.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

-keep class com.a.labs.data.local.room.entity.** { *; }
-keep class com.a.labs.data.local.room.dao.** { *; }

-keep class androidx.media3.** { *; }
-keep class com.a.labs.worker.PlaybackService { *; }

-keep class com.tom.roush.pdfbox.** { *; }
-dontwarn com.tom.roush.pdfbox.**

-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

-keep class kotlin.Metadata { *; }