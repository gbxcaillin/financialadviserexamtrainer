import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from android/keystore.properties (not committed):
//   storeFile=/path/to/upload-keystore.jks
//   storePassword=…  keyAlias=…  keyPassword=…
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(name: String): String = project.findProperty(name) as String

android {
    namespace = "com.gbxps.fasea"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gbxps.fasea"
        minSdk = 26
        targetSdk = 36
        // Must be higher than the versionCode currently live on Google Play.
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Google's public AdMob test IDs
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/5224354917\"")
            buildConfigField("String", "INTERSTITIAL_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            manifestPlaceholders["admobAppId"] = prop("fasea.admob.appId")
            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"${prop("fasea.admob.rewardedUnitId")}\"")
            buildConfigField("String", "INTERSTITIAL_AD_UNIT_ID", "\"${prop("fasea.admob.interstitialUnitId")}\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.17.1")
    // Google Play Billing
    implementation("com.android.billingclient:billing-ktx:8.3.0")
    // AdMob
    implementation("com.google.android.gms:play-services-ads:25.5.0")
}
