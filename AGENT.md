# ALabs - Smart Audio Library (مكتبة الكتب الصوتية الذكية)

## 📌 نظرة عامة على المشروع (Overview)
تطبيق أندرويد متطور يعتمد على المعمارية النظيفة (Clean Architecture) وتقنية MVVM، مخصص لتحويل ملفات PDF الممسوحة ضوئياً أو النصية إلى كتب صوتية وتفاعلية. يعتمد التطبيق على تقنيات الذكاء الاصطناعي (Gemini Vision OCR & TTS) لاستخراج النصوص وتوليد الصوت، مع دعم لمحركات صوتية متعددة (System TTS, ElevenLabs, Gemini TTS).

---

## 🏗️ المعمارية والتقنيات المستخدمة (Tech Stack)
* **واجهة المستخدم:** Jetpack Compose (100% Declarative UI).
* **المعمارية:** MVVM (Model-View-ViewModel).
* **إدارة الحالات المتزامنة:** Kotlin Coroutines & StateFlow.
* **قاعدة البيانات:** Room Database للمحافظة على حالة الكتب والصفحات المجزأة.
* **العمليات في الخلفية:** WorkManager (Foreground Services) لضمان عدم توقف معالجة PDF.
* **الشبكات:** OkHttp3 (مع مهلة مخصصة Timeout 15m).
* **إدارة الوسائط:** AndroidX Media3 (ExoPlayer) للتشغيل في الخلفية.

---

## 📂 هيكل الملفات وتدفق العمل (Project Structure)

### 1. طبقة الأساسيات (Core & Domain)
* `app/src/main/java/com/a/labs/core/GeminiModels.kt`: يحتوي على ثوابت أسماء نماذج الذكاء الاصطناعي المعتمدة.
* `app/src/main/java/com/a/labs/core/AppLogger.kt`: نظام تسجيل أحداث محلي (Local Logger) يُستخدم في "وضع المطور" لحفظ الأخطاء وتتبع العمليات في ملف نصي.
* `app/src/main/java/com/a/labs/domain/usecase/PdfChunkerUseCase.kt`: مسؤول عن قراءة ملف الـ PDF الأصلي، وتجزئته (Chunking) إلى ملفات PDF صغيرة بناءً على النطاق المحدد.

### 2. طبقة قواعد البيانات (Local Data)
* `app/src/main/java/com/a/labs/data/local/SettingsManager.kt`: يستخدم `DataStore` لحفظ مفاتيح الـ API، وإعدادات المستخدم (حجم الدفعة، وضع المطور).
* `app/src/main/java/com/a/labs/data/local/room/AppDatabase.kt`: إعداد قاعدة بيانات Room.
* `app/src/main/java/com/a/labs/data/local/room/dao/BookDao.kt`: واجهة الوصول للبيانات (Insert, Query, Update).
* `app/src/main/java/com/a/labs/data/local/room/entity/BookEntity.kt`: يمثل الكتاب العام.
* `app/src/main/java/com/a/labs/data/local/room/entity/ChunkEntity.kt`: يمثل الدفعات (نطاق الصفحات وحالة معالجتها PENDING, PROCESSING, COMPLETED, FAILED).
* `app/src/main/java/com/a/labs/data/local/room/entity/PageEntity.kt`: يمثل كل صفحة بنصها المستخرج (Markdown) ومسار ملفها الصوتي.

### 3. طبقة الشبكات (Remote Data / API Clients)
* `app/src/main/java/com/a/labs/data/remote/api/GeminiFilesClient.kt`: لرفع دفعات الـ PDF المقطوعة إلى خوادم جوجل للحصول على URI.
* `app/src/main/java/com/a/labs/data/remote/api/GeminiOcrClient.kt`: يقوم بإرسال الـ URI مع الأوامر (Prompts) واستخراج الرد بصيغة JSON مرنة جداً.
* `app/src/main/java/com/a/labs/data/remote/api/GeminiTtsClient.kt` & `ElevenLabsClient.kt`: عملاء لتوليد الملفات الصوتية وحفظها محلياً.
* `app/src/main/java/com/a/labs/data/remote/dto/...`: ملفات Data Transfer Objects (DTOs) للتعامل مع الـ JSON.

### 4. طبقة معالجة الصوت والخدمات (Audio & Workers)
* `app/src/main/java/com/a/labs/data/audio/SystemTtsWrapper.kt`: يغلف محرك نطق النظام ويعمل كـ Stream فوري (لا يحفظ ملفات).
* `app/src/main/java/com/a/labs/data/audio/AudioPlayerController.kt`: المتحكم الرئيسي بالصوت، يربط بين Media3، الواجهة، والـ API.
* `app/src/main/java/com/a/labs/worker/PdfExtractionWorker.kt`: العامل المتزامن الذي يعمل كـ Foreground Service ويقود عملية (التقسيم -> الرفع -> الاستخراج -> الحفظ).
* `app/src/main/java/com/a/labs/worker/PlaybackService.kt`: خدمة MediaSessionService لتشغيل الصوت في الخلفية.

### 5. طبقة واجهة المستخدم (UI & ViewModels)
* `app/src/main/java/com/a/labs/ui/library/LibraryScreen.kt` & `LibraryViewModel.kt`: شاشة الإضافة وتحديد نطاق المعالجة.
* `app/src/main/java/com/a/labs/ui/reader/ReaderScreen.kt` & `ReaderViewModel.kt`: شاشة القراءة التفاعلية، تراقب قاعدة البيانات، وتتحكم بالصوت.
* `app/src/main/java/com/a/labs/ui/settings/SettingsScreen.kt` & `LogsScreen.kt`: إعدادات التطبيق، إدخال المفاتيح، وعرض السجلات للمطورين.

---

## 🔄 كيف تعمل تدفقات البيانات (Data Flow)?
1. المستخدم يختار PDF. الـ `LibraryViewModel` ينسخه محلياً لتجنب فقدان الصلاحية.
2. يُسأل المستخدم عن "نطاق الصفحات"  (مثلاً 1 إلى 20).
3. يتم إطلاق `PdfExtractionWorker` كخدمة أمامية مع إشعار دائم.
4. الوركر يقوم بإنشاء سجلات الـ `ChunkEntity` (الدفعات) في قاعدة البيانات فوراً بحالة `PENDING`.
5. الواجهة (`ReaderScreen`) تفتح فوراً وتراقب حالة هذه الدفعات.
6. الوركر يبدأ: التقسيم محلياً -> الرفع لـ Gemini Files -> طلب الـ OCR -> حفظ الصفحات في `PageEntity`.
7. بمجرد ظهور `PageEntity`، الواجهة تتحدث تلقائياً (Reactive UI).
8. عند ضغط تشغيل الصوت، `AudioPlayerController` يولد الصوت في الـ Background (أو يقرأه عبر النظام فوراً) ويرسله لـ `Media3`.

---

## 🔥 التعديلات الأخيرة (Recent Modifications - v1.1.0)
تم إجراء تعديلات جراحية عميقة لحل مشاكل الاتصال والتزامن:

1. **معالجة مشاكل Timeout (المهلة الزمنية):**
   * تم رفع المهلة الزمنية لـ `OkHttpClient` في الـ `PdfExtractionWorker` و `AudioPlayerController` إلى **15 دقيقة** لتجنب فشل جيميناي مع الدفعات الكبيرة.
2. **الاستجابة التفاعلية للأخطاء (Reactive Error Handling):**
   * تعديل `PdfExtractionWorker` ليقوم بتقسيم الدفعات (Chunks) وإدراجها في قاعدة البيانات **قبل** التحقق من مفتاح الـ API والاتصال بالشبكة. 
   * في حال الفشل (بسبب انقطاع الإنترنت أو نقص المفتاح)، تتحول حالة الـ Chunk إلى `FAILED`.
   * الـ `ReaderViewModel` يراقب هذه الحالة عبر `statusMonitorJob`، مما يظهر زر **"إعادة المحاولة"** (Retry) للمستخدم فوراً لتشغيل الوركر مرة أخرى من حيث توقف.
3. **التشغيل المستقل بالخلفية (Background Audio Scope):**
   * تم نقل عمليات توليد الصوت في `AudioPlayerController` إلى `CoroutineScope(Dispatchers.IO + SupervisorJob())`. هذا يضمن عدم تدمير طلب توليد الصوت إذا قام المستخدم بإغلاق شاشة القارئ.
4. **محرك النظام الفوري (System TTS Streaming):**
   * تم تحويل `SystemTtsWrapper` للعمل بطريقة البث المباشر الفوري (Real-time). لم يعد يحفظ ملفات `Wav`، بل يقرأ النص مباشرة، وتم تعطيل زر "تصدير الصوت" برمجياً إذا كان المحرك المستخدم هو النظام.
5. **ترجمة رموز الخطأ (Error Translation):**
   * التقاط خطأ جيميناي `503` (Server Overload) و `404` وترجمتها إلى نصوص واضحة للمستخدم تقترح "تغيير النموذج" أو "المحاولة لاحقاً".
6. **تصحيح النماذج (Model Naming Fix):**
   * تحديث أسماء النماذج في `GeminiModels.kt` لتتطابق بدقة مع توثيق جوجل (مثل `gemini-3-flash-preview` و  `gemini-2.5-flash-preview-tts`).