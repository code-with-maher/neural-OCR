plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.a.labs"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.a.labs"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        // أبقيت النسخة كما هي بتاريخ اليوم
        versionName = "1.0.0-beta-15-04-2026"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // تعريف توقيع موحد لاستخدامه في التصحيح والريس
        create("mainConfig") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // في حال عدم توفر متغيرات البيئة، سيستخدم التوقيع الافتراضي تلقائياً
                // لكي لا يتوقف بناء المشروع (Build)
            }
        }
    }

    buildTypes {
        debug {
            // نستخدم نفس التوقيع لكي يقبل الهاتف التحديث/التثبيت
            signingConfig = signingConfigs.getByName("mainConfig")
            
            // ميزات التصحيح
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            
            // لاحقة لتمييز نسخة المطورين (اختياري، يمكنك حذفها إذا أردت تطابقاً تاماً)
            applicationIdSuffix = ".debug"
        }

        release {
            signingConfig = signingConfigs.getByName("mainConfig")
            
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        }
    }

    buildFeatures {
        compose = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.splashscreen)
    
    // UI & Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    
    // Navigation & Data
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    
    // Database (Room)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Networking & Media
    implementation(libs.okhttp)
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    
    // Background Tasks
    implementation(libs.androidx.work.runtime.ktx)
}
