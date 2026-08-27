import org.gradle.api.tasks.testing.Test

val customBuildDir = providers.gradleProperty("customBuildDir").orNull
if (customBuildDir != null) {
    layout.buildDirectory.set(file(customBuildDir))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "llc.slacker.openime"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "llc.slacker.openime"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Physical phones in scope are arm64; x86_64 keeps the existing
            // emulator regression path available without shipping 32-bit
            // native Rime binaries we do not need.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // sherpa-onnx can map the bundled models directly from the APK only
        // when these large assets are stored without ZIP compression.
        noCompress += listOf("onnx", "txt")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// Kotlin 2.1 writes JVM unit-test classes to tmp/kotlin-classes while this
// standalone module's Android test task may expose only the empty javac dir.
// Keep the JVM unit-test task discoverable and executable on every Windows
// checkout, including paths containing non-ASCII characters.
tasks.withType<Test>().configureEach {
    val kotlinTestClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")
    testClassesDirs = files(kotlinTestClasses)
    // `+=` is not reliably materialized by the AGP/Kotlin 2.1 task wiring on
    // Windows paths containing non-ASCII characters. Put the Kotlin output
    // explicitly at the front of the test runtime classpath so JUnit can
    // discover the classes it just compiled.
    classpath = files(kotlinTestClasses) + classpath
}
