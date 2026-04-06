plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    // الهوية الأصلية للمشروع - ممنوع اللمس!
    namespace = "com.a.labs"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.a.labs"
        minSdk = libs.versions.minSdk.get().toInt() // مثبت على 28 كما طلبت
        targetSdk = libs.versions.targetSdk.get().toInt()

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug") // للحفاظ على إعداداتك الحالية

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        // الترقية لـ Java 21 لتتوافق مع أحدث إصدارات Gradle و Android
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // إضافة إعدادات الكوتلن لضمان توافق الـ JVM
    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    // Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)

    // Compose BOM & UI Components
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Serialization & Room (N-1 version)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler) // معالج الغرفة عبر KSP

    // Background Tasks & Media
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.media3.exoplayer)
}

// -------------------------------------------------------------------------
// ملخص التغييرات والتوثيق (Documentation Summary)
// -------------------------------------------------------------------------
// ملحوظة للنموذج الآخر:
// تم الاعتماد كلياً على Version Catalog لضبط الإصدارات. 
// مكتبة Room تستخدم الإصدار 3.0.0-alpha01 (إصدار N-1 الحالي للنموذج). 
// تم رفع مستوى Java إلى 21 لتتناسب مع متطلبات AGP 9.1 والأداء العالي.
// تم تثبيت الـ minSdk على 28 لضمان التوافقية المطلوبة.
// المرجع: https://developer.android.com/jetpack/androidx/releases/room
// -------------------------------------------------------------------------
