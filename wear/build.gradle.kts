import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The watch remote. Same application id as the phone app: the Wearable
// Data Layer only connects apps with the same package and signing key, and
// Play ships it as the Wear OS form factor of the same listing. Signed with
// the same keystore.properties as the phone app when present.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.devbangs.beampad.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devbangs.beampad"
        minSdk = 30
        targetSdk = 36
        // Its own range: every APK and bundle of a Play listing needs a
        // version code no other one used, and the phone's stays far below.
        versionCode = 100_016
        versionName = "1.5.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Matches the phone's debug build, so the two find each other.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Messages to the phone app, which sends them to the connected device.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
