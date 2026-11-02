plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

// whisper.cpp's Android module is used as is, but two of its settings are
// overridden here, after its own build.gradle has run:
//  - CMAKE_BUILD_TYPE=Release in every variant. A debug APK otherwise builds
//    the native code with -O0 (whisper's CMakeLists only adds -O3 when the
//    build type is not Debug), which made transcription 10-30x slower in
//    0.11.3 and earlier.
//  - the NDK we actually have, and arm64 only.
subprojects {
    if (path == ":whisper") {
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<com.android.build.api.variant.LibraryAndroidComponentsExtension> {
                finalizeDsl { android ->
                    android.ndkVersion = "27.2.12479018"
                    android.defaultConfig.ndk.abiFilters.clear()
                    android.defaultConfig.ndk.abiFilters += "arm64-v8a"
                    android.defaultConfig.externalNativeBuild.cmake.arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
    }
}
