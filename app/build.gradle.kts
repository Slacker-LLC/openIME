import org.gradle.api.tasks.testing.Test

val customBuildDir = providers.gradleProperty("customBuildDir").orNull
if (customBuildDir != null) {
    layout.buildDirectory.set(file(customBuildDir))
}

fun releaseValue(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull

val releaseKeystorePath = releaseValue("OPENIME_KEYSTORE_PATH")
val releaseKeystorePassword = releaseValue("OPENIME_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseValue("OPENIME_KEY_ALIAS")
val releaseKeyPassword = releaseValue("OPENIME_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

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
        versionCode = 4
        versionName = "1.0.3"

    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "openIME release signing is not configured; assembleRelease will produce an unsigned APK",
                )
            }
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
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// Kotlin 2.1 writes JVM unit-test classes to tmp/kotlin-classes while this
// standalone module's Android test task may expose only the empty javac dir.
// Keep the JVM unit-test task discoverable and executable on every Windows
// checkout, including paths containing non-ASCII characters.
tasks.withType<Test>().configureEach {
    if (!name.startsWith("test") || !name.endsWith("UnitTest")) return@configureEach
    val testCompilation = name.removePrefix("test").replaceFirstChar { it.lowercaseChar() }
    val kotlinTestClasses = layout.buildDirectory.dir("tmp/kotlin-classes/$testCompilation")
    testClassesDirs = files(kotlinTestClasses)
    // `+=` is not reliably materialized by the AGP/Kotlin 2.1 task wiring on
    // Windows paths containing non-ASCII characters. Put the Kotlin output
    // explicitly at the front of the test runtime classpath so JUnit can
    // discover the classes it just compiled.
    classpath = files(kotlinTestClasses) + classpath
    // Several unit tests read Rime schemas straight off disk (for example
    // RimeFuzzySchemaTest). Without a declared input Gradle reports
    // UP-TO-DATE after an asset edit and the tests never re-run, which hides
    // a broken schema behind a green local build.
    inputs.dir(layout.projectDirectory.dir("src/main/assets"))
        .withPropertyName("mainAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
