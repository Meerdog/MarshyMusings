plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.vmm5.notes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vmm5.notes"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "0.16.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // The project's own debug key, so every build installs over the last one
    // wherever it was built (a new machine's own debug key would not). It is
    // not in the public repository: put your copy at app/marshy-debug.keystore.
    // Without it the build uses this machine's debug key, and the APK will not
    // install over one signed with the project key.
    val projectKey = file("marshy-debug.keystore")
    signingConfigs {
        getByName("debug") {
            if (projectKey.exists()) {
                storeFile = projectKey
                storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
            }
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":whisper"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    testImplementation("junit:junit:4.13.2")
}
