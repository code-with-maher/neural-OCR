
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.a.labs"

    compileSdk {
        version = release(
            libs.versions.compileSdk.get().toInt()
        ) {
            minorApiLevel =
                libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "com.a.labs"

        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("mainConfig") {
            storeFile = file(
                providers.environmentVariable("KEYSTORE_PATH").get()
            )

            storePassword =
                providers.environmentVariable("KEYSTORE_PASSWORD").get()

            keyAlias =
                providers.environmentVariable("KEY_ALIAS").get()

            keyPassword =
                providers.environmentVariable("KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("mainConfig")
        }

        release {
            isDebuggable = false

            optimization {
                enable = true
            }

            signingConfig = signingConfigs.getByName("mainConfig")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_26
        targetCompatibility = JavaVersion.VERSION_26
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_26)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))

    // Activity and Core
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Compose Preview and Tooling
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle and Navigation
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Networking
    implementation(libs.okhttp)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // PDFBox Android
    implementation(libs.pdfbox.android)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
}
