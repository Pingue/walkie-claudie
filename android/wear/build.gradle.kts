import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Must equal the phone app's id (and be signed with the same key) for the Wearable Data Layer to connect them.
    namespace = "com.bookmari.walkie.wear"

    compileSdk = 36

    defaultConfig {
        applicationId = "com.bookmari.walkie"
        minSdk = 30 // Wear OS 3, which the first Pixel Watch shipped with
        targetSdk = 36
        versionCode = providers.gradleProperty("walkie.versionCode").orNull?.toInt() ?: 101
        versionName = providers.gradleProperty("walkie.versionName").getOrElse("0.1.1")
    }

    // Same release key as :app, or the phone and watch apps will not see each other.
    val keyProps = file(providers.gradleProperty("walkie.keystore").getOrElse(System.getProperty("user.home") + "/.android-keys/walkie-claudie/keystore.properties"))
    signingConfigs {
        if (keyProps.exists()) create("release") {
            val k = Properties().apply { keyProps.inputStream().use { load(it) } }
            storeFile = file(k.getProperty("storeFile"))
            storePassword = k.getProperty("storePassword")
            keyAlias = k.getProperty("keyAlias")
            keyPassword = k.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
