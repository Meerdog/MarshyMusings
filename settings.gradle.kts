pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "VMM5 Notes"
include(":app")
// whisper.cpp's own Android library module (JNI + CMake), used as is.
include(":whisper")
project(":whisper").projectDir = file("whisper.cpp/examples/whisper.android/lib")
