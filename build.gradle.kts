plugins {
    id("com.android.application") version "8.0.2" apply false
    id("com.android.library") version "8.0.2" apply false
    kotlin("android") version "1.8.0" apply false
    kotlin("kapt") version "1.8.0" apply false

    // مهم لفحص التحديثات
    id("com.github.ben-manes.versions") version "0.51.0"
}