import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)

    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)

    alias(libs.plugins.gms)

    id(libs.plugins.parcelize.get().pluginId)

    // Annotation processing
    alias(libs.plugins.ksp)
    id(libs.plugins.kapt.get().pluginId)
}

// --- Release signing config (loads creds from keystore/keystore.properties) ---
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "com.trigger.app"
    compileSdk = 34

    defaultConfig {
        val majorRelease = 1
        val defaultRelease = 0
        val minorRelease = 0

        applicationId = "com.trigger.app"
        minSdk = 24
        targetSdk = 34
        versionCode = (majorRelease * 100) + (defaultRelease * 10) + minorRelease
        versionName = "$majorRelease.$defaultRelease.$minorRelease"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.splashScreen)
    implementation(libs.androidx.navigation)


    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Extended icons (Mic, MicOff, VolumeUp, VolumeOff, CallEnd, etc.) — used
    // by call screens. Without this, Icons.Rounded.Mic / CallEnd etc. fail to
    // resolve at compile time.
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.runtime)
    implementation(libs.androidx.runtime.livedata)

    implementation(project(":compose_ccp"))

    // Compose UI libraries
    implementation(libs.compose.glide)
    implementation(libs.compose.coil)
    implementation(libs.cloudy)
    implementation(libs.compose.googleFonts)
    implementation(libs.compose.imageCropper)
    implementation(libs.compose.audiowaveform)
    implementation(libs.compose.amplituda)


    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.functions)

    // WebRTC — used by the Calls feature for real-time audio/video.
    // Includes prebuilt native binaries for arm64-v8a / armeabi-v7a / x86 / x86_64.
    implementation(libs.stream.webrtc)

    // DI using Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.coreCoroutines)
    implementation(libs.koin.compose)

    // Network library
//    implementation(libs.retrofit)
//    implementation(libs.retrofit.moshiconverter)
//    implementation(libs.retrofit.okhttp.logger)

    // Serialization
    implementation(libs.kotlin.serialization)
    implementation(libs.moshi)
    implementation(libs.moshi.reflect)
//    ksp(libs.ksp.moshi.codegen)

//    Local caching
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Others
    implementation(libs.logging.timber)
    implementation(libs.jodaTime)
    implementation(libs.libphonenumber)


    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}