plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

val releaseVersionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
val releaseVersionName = System.getenv("APP_VERSION_NAME") ?: "0.0.0-dev"

android {
    namespace = "com.hiromishikata.timetrackingradio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hiromishikata.timetrackingradio"
        minSdk = 26
        targetSdk = 34
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    signingConfigs {
        create("releaseKey") {
            storeFile = file("testkey_untrusted.jks")
            storePassword = "timetrackingradio"
            keyAlias = "timetrackingradio"
            keyPassword = "timetrackingradio"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("releaseKey")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("releaseKey")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        disable += setOf("OldTargetApi")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
