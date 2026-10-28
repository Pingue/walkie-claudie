import java.time.LocalDateTime
import java.util.Properties
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bookmari.walkie"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bookmari.walkie"
        minSdk = 29
        targetSdk = 36
        // The release workflow passes the semver it derived from commit messages; local builds keep these.
        versionCode = providers.gradleProperty("walkie.versionCode").orNull?.toInt() ?: 101
        versionName = providers.gradleProperty("walkie.versionName").getOrElse("0.1.1")
        // Shown in the app so a screenshot tells which build is installed.
        fun git(vararg a: String) = providers.exec { commandLine("git", *a) }.standardOutput.asText.get().trim()
        val dirty = if (git("status", "--porcelain").isEmpty()) "" else "+"
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        buildConfigField("String", "BUILD", "\"${git("rev-parse", "--short", "HEAD")}$dirty $time\"")
    }

    // Release key lives outside the repo (path overridable with -Pwalkie.keystore=...); without it a release build is debug-signed.
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

    buildFeatures {
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // QR scanning via Google Play services: no camera permission, no bundled scanner.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
}
