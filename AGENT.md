# 🤖 AI Agent Context File (AGENT.md)
**Project Name:** ALabs (Smart OCR & TTS Audio Reader)
**Package Name:** `com.a.labs`
**Language:** Kotlin (2.3.20) | **UI:** Jetpack Compose (No XML Views)
**Architecture:** MVI/MVVM with strict Single Responsibility & Modularity.

## ⚠️ القواعد الذهبية للوكيل (Golden Rules for AI Agent)
1. **لا تعليقات داخل الكود (No Inline Comments):** الكود يجب أن يشرح نفسه (Self-documenting). ممنوع كتابة تعليقات داخل دوال أو ملفات الكوتلن.
2. **أحدث التقنيات (Bleeding Edge):** استخدم دائماً أحدث ممارسات Compose و Kotlin (مثلاً `kotlinx.serialization` بدلاً من `Gson`).
3. **فصل المسؤوليات (Modularity):** كل كلاس، واجهة، أو وظيفة يجب أن تكون في ملفها الخاص ومسارها المستقل. لا تدمج شاشتين أو وظيفتين في ملف واحد.
4. **التوافقية (Accessibility - A11y):** التطبيق موجه لخدمة المكفوفين ومستخدمي قارئات الشاشة (TalkBack). كل عنصر UI يجب أن يدعم الـ Semantics والـ Content Description الديناميكي.

---

## 📂 المرحلة الحالية: الملفات المنجزة (Current State & Existing Files)
هذه الملفات تم إنشاؤها بالفعل وتعمل بنجاح. **لا تقم بإعادة إنشائها أو تعديلها إلا إذا طُلب منك ذلك صراحة:**

### 1. إعدادات البناء والبيئة (Build & Config)
- `/.github/workflows/build.yml`: ملف CI/CD باستخدام GitHub Actions لبناء نسخة Release (APK) وإرسالها عبر Telegram باستخدام سكربت بايثون.
- `/build.gradle.kts` (Root): ملف الجريدل الأساسي (يحتوي فقط على الـ Plugins Alias).
- `/settings.gradle.kts`: إدارة المستودعات (Repositories) وإعدادات الـ Resolution.
- `/gradle.properties`: إعدادات الـ JVM (مخصص بـ 4GB RAM) وتفعيل الكاش و R8 Full Mode.
- `/gradle/libs.versions.toml`: الكتالوج (Version Catalog) الذي يحتوي على جميع إصدارات المكتبات (AGP 9.1.0, Compose BOM 2026.03.01, OkHttp 5.3.2).
- `/app/build.gradle.kts`: إعدادات موديول التطبيق، `minSdk 28`، تفعيل Compose، وربط المكتبات (ملاحظة: سيتم استبدال `Gson` بـ `kotlinx.serialization` لاحقاً).
- `/app/proguard-rules.pro`: قواعد ضغط الكود. تم تنظيفه من ملفات البايندينج، وتمت حماية كلاسات `OkHttp` و `Kotlin Metadata`.

### 2. الكود المصدري (Source Code)
- `/app/src/main/AndroidManifest.xml`: المانيفيست المنظف. يحتوي على أذونات الإنترنت وقراءة الوسائط، ويستخدم ثيم `Theme.Material.Light.NoActionBar` كجسر مؤقت للإقلاع بدون شريط علوي. (تم حذف `colors.xml` و `styles.xml` نهائياً).

**المسار الأساسي:** `/app/src/main/java/com/a/labs/`
- `core/GeminiModels.kt`: (Object) يحتوي على ثوابت بأسماء نماذج Gemini المدعومة (`FLASH_3_1_LITE`, `FLASH_3_0`, `FLASH_2_5`).
- `data/local/SettingsManager.kt`: كلاس يدير تفضيلات المستخدم باستخدام `DataStore`. يحتوي على `Flow` لجلب وحفظ: مفتاح Gemini، مفتاح ElevenLabs، محرك הـ TTS المختار، ونموذج Gemini المفضل.
- `ui/settings/SettingsScreen.kt`: شاشة Compose تعتمد على `SettingsManager`. تحتوي على حقول إدخال مفاتيح الـ API، وقوائم منسدلة (DropdownMenu) لاختيار محرك النطق ونموذج الذكاء الاصطناعي.

---

## 🏗️ خارطة الطريق: الملفات المخطط إنشاؤها (Planned Architecture & Future Files)
هذه الملفات والمسارات سيتم إنشاؤها في الجلسات القادمة. يجب على الوكيل (AI) الالتزام بهذه الهيكلة عند كتابة الأكواد:

### 1. التصميم والملاحة (Theme & Navigation)
- `ui/theme/Theme.kt`: الثيم الأساسي لـ Compose.
- `ui/theme/Color.kt` & `ui/theme/Type.kt`: الألوان والخطوط.
- `ui/navigation/NavGraph.kt`: لإدارة التنقل بين الشاشات (`LibraryScreen`, `ReaderScreen`, `SettingsScreen`).

### 2. واجهات المستخدم الأساسية (Core UI Screens)
- `ui/library/LibraryScreen.kt`: الشاشة الرئيسية. تعرض قائمة الكتب المحفوظة. تحتوي على زر (إضافة كتاب PDF) عبر `Storage Access Framework`.
- `ui/reader/ReaderScreen.kt`: شاشة قراءة الكتاب. يتم عرض النص فيها كـ `LazyColumn` مقسم لفقرات لدعم `TalkBack`. تحتوي على أزرار تحكم بالصوت (سابق، تالي، تشغيل، ترجيع) وقائمة خيارات (ترجمة، تحويل لصوت).

### 3. قاعدة البيانات المحلية (Local Database - Room)
- `data/local/room/AppDatabase.kt`: إعداد قاعدة البيانات.
- `data/local/room/dao/BookDao.kt`: استعلامات الكتب وحالة الدفعات (Chunks).
- `data/local/room/entity/BookEntity.kt` & `ChunkEntity.kt`: جداول تمثل الكتاب، دفعات الـ PDF المقطوعة، والـ `Files API URI` الخاص بكل دفعة لضمان عدم  ضياعها.

### 4. العمليات الخلفية (Background Processing - WorkManager)
- `worker/PdfProcessorWorker.kt`: يقوم بتقسيم ملف الـ PDF عبر مكتبة `PdfBox-Android` إلى دفعات (15 صفحة افتراضياً).
- `worker/GeminiUploadWorker.kt`: يرفع الدفعات إلى `Google Files API` ويحفظ الـ URI في قاعدة البيانات المحلية.
- `worker/AudioGenerationWorker.kt`: يتصل بـ (ElevenLabs / Gemini TTS) لتحويل النصوص إلى ملفات صوتية وحفظها في التخزين المحلي.

### 5. الشبكات ومعالجة الذكاء الاصطناعي (Network & AI Domain)
- `data/remote/api/FilesApi.kt`: للتعامل مع مساحة تخزين جوجل المؤقتة.
- `data/remote/api/GeminiApi.kt`: لإرسال الـ URI + `System Prompt` وطلب `JSON Structured Output` (ماركداون).
- `domain/usecase/SmartChunkingUseCase.kt`: خوارزمية ذكية لتقسيم النصوص الكبيرة قبل إرسالها للـ TTS بناءً على أقرب علامة ترقيم (نقطة، فاصلة) لتجنب القطع العشوائي للكلمات.

---

## ⚙️ آليات العمل الجوهرية (Core Workflows to strictly follow)
1. **معالجة الـ PDF بدلاً من الرندرة:** لا نستخدم `PdfRenderer` لتجنب استهلاك الرام (لا نحول PDF لصور). نستخدم `PdfBox` لاقتطاع ملف PDF أصغر، ثم نرفعه لـ Files API، ثم نرسل الـ URI لـ Gemini.
2. **مرونة الأخطاء (Resilience):** أي عملية شبكية فاشلة لا تعيد العملية من الصفر. الـ Worker يجب أن يقرأ الـ URI المحفوظ في الـ `Room DB` ويكمل المحاولة من حيث توقف.
3. **هندسة الأوامر (Prompting):** في طلب Gemini، يجب إرشاد النموذج إلى (تجاهل التذييل وأرقام الصفحات)، وصف الصور كـ Text، وترجمة النصوص إلى العربية إذا تم تفعيل ميزة "الترجمة الفورية" من  الإعدادات.