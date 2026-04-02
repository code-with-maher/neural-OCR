plugins {
    id("com.android.application") version "8.0.2" apply false
    id("com.android.library") version "8.0.2" apply false
    kotlin("android") version "1.8.0" apply false
    kotlin("kapt") version "1.8.0" apply false
}

android {
    compileSdk = 34
}

dependencies {
    implementation("androidx.compose.ui:ui:1.5.0")
    implementation("androidx.compose.material3:material3:1.5.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.5.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.5.0")
}