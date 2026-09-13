import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.autov.sms"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autov.sms"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // --- Load keystore props (from app/keystore.properties) ---
    val keystoreProps = Properties().apply {
        val propsFile = file("keystore.properties")
        if (propsFile.exists()) {
            FileInputStream(propsFile).use { load(it) }
        }
    }

    signingConfigs {
        create("release") {
            // Only configure if file exists (so debug builds still sync without it)
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        // (optional) sign debug with same keystore:
        // debug { signingConfig = signingConfigs.getByName("release") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.work.runtime)
    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
