plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
val releaseVersionName = System.getenv("APP_VERSION_NAME") ?: "0.0.0"

android {
    namespace = "com.hiromishikata.timetrackingradio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hiromishikata.timetrackingradio"
        minSdk = 26
        targetSdk = 36
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
        checkDependencies = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test> {
    systemProperty("roborazzi.test.record", "true")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.core:core-ktx:1.16.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.0")
}
