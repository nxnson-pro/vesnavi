// :app (Android, needs the SDK) + :navcore (pure Kotlin, JVM-tested on Linux).
// AGP and the Kotlin plugins are declared here once, so they share one classloader.
plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
