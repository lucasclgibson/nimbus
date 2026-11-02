import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing never lives in the repo. CI passes the key in environment variables; locally,
// NIMBUS_SIGNING_PROPERTIES can name a properties file with the same four values. Without either,
// release builds come out unsigned (app-release-unsigned.apk).
val signing: Map<String, String> = run {
    System.getenv("NIMBUS_SIGNING_PROPERTIES")?.takeIf { it.isNotBlank() }?.let { path ->
        val p = Properties().apply { file(path).inputStream().use { load(it) } }
        return@run p.stringPropertyNames().associateWith { p.getProperty(it) }
    }
    mapOf(
        "storeFile" to "NIMBUS_KEYSTORE", "storePassword" to "NIMBUS_KEYSTORE_PASSWORD",
        "keyAlias" to "NIMBUS_KEY_ALIAS", "keyPassword" to "NIMBUS_KEY_PASSWORD",
    ).mapNotNull { (key, env) -> System.getenv(env)?.takeIf { it.isNotBlank() }?.let { key to it } }.toMap()
}
val signed = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { it in signing }

// The version is set in one place, gradle.properties (nimbus.version, semantic versioning). The version
// code follows from it, so every release is an upgrade: 1.4.2 -> 10402.
val appVersion = providers.gradleProperty("nimbus.version").get()
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

android {
    namespace = "io.github.lucasclgibson.nimbus"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lucasclgibson.nimbus"
        minSdk = 25
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        // Fire TV sticks run a 32-bit userspace; keep arm64 for other Android TV boxes.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        if (signed) {
            create("release") {
                storeFile = file(signing.getValue("storeFile"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signed) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // FFmpeg audio decoders (AC3/E-AC3/DTS/TrueHD/MP2...) so odd IPTV audio never plays silent.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    val room = "2.7.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    implementation("io.coil-kt.coil3:coil-svg:3.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
