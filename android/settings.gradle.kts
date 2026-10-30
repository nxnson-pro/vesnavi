pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "vesnavi"
include(":navcore")
// `-Pvesnavi.coreOnly=true` skips the Android app, for machines without the Android SDK
// (the shared core still builds and tests, like `swift test` for the iOS NavCore).
val coreOnly = providers.gradleProperty("vesnavi.coreOnly").orNull == "true"
if (coreOnly) rootProject.buildFileName = "core-only.gradle.kts" else include(":app")
