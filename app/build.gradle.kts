plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.atuy.hoyodashboard"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "com.atuy.hoyodashboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":hoyo-api"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
