import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin (JVM) so it builds and unit-tests on Linux without the Android SDK.
plugins { kotlin("jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    // Android ships org.json; the JVM build only compiles and tests against it.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
