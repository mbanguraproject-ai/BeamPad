import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing credentials live outside version control. Release builds fall back
// to unsigned if the file is absent, rather than failing the whole build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.devbangs.beampad"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devbangs.beampad"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"
    }

    buildFeatures {
        viewBinding = true
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

    // Robolectric renders screens on the JVM for the screenshot tests, which
    // CI publishes so layouts can be reviewed without a device.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.maxHeapSize = "3g"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.window:window:1.3.0")
    // ExploreByTouchHelper: TalkBack reads and presses each key of the full keyboard.
    implementation("androidx.customview:customview:1.1.0")

    // Ads. UMP is required: consent must be collected before any ad request.
    implementation("com.google.android.gms:play-services-ads:25.0.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")

    // Billing. v8+ is a Play publishing requirement as of Aug 2026.
    implementation("com.android.billingclient:billing:9.1.0")

    // In-app review. Play decides whether the dialog appears at all, so the
    // caller must treat "nothing happened" as the normal outcome.
    implementation("com.google.android.play:review:2.0.2")

    // In-app updates: new releases download in the background and offer a
    // restart; high-priority releases use the immediate flow.
    implementation("com.google.android.play:app-update:2.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.6.1")
}
