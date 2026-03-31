# القواعد التي أرسلتها أنت (ممتازة للضغط)
-repackageclasses ''
-ignorewarnings
-dontwarn
-dontnote

# حماية مكتبة OkHttp (ضروري لأنها تعتمد على الانعكاس Reflection)
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# حماية مكتبة Kotlin (لكي لا تضيع أسماء الكلاسات الأساسية)
-keep class kotlin.Metadata { *; }

# إذا كنت تستخدم ViewBinding كما في ملفك، أضف هذه لضمان عملها
-keep class .databinding. { *; }
