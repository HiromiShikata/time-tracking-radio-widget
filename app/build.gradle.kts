plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
val releaseVersionName = System.getenv("APP_VERSION_NAME") ?: "0.0.0"

val RELEASE_KEY_ALIAS = "time-tracking-radio-widget"
val RELEASE_SIGNING_VARIABLES = listOf("RELEASE_KEYSTORE_FILE", "RELEASE_KEYSTORE_PASSWORD")

fun releaseSigningValue(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

val validateReleaseSigning by tasks.registering {
    description = "Fails unless every release signing input is set in the environment."
    doLast {
        val missing = RELEASE_SIGNING_VARIABLES.filter { releaseSigningValue(it) == null }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing needs environment variable(s): ${missing.joinToString(", ")}. " +
                    "No unsigned or debug-signed release is built. See the Release signing section of README.md.",
            )
        }
        val keystore = file(releaseSigningValue("RELEASE_KEYSTORE_FILE")!!)
        if (!keystore.isFile) {
            throw GradleException("RELEASE_KEYSTORE_FILE does not point to a readable file: ${keystore.path}")
        }
    }
}

afterEvaluate {
    tasks.named("preReleaseBuild") {
        dependsOn(validateReleaseSigning)
    }
}

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
        create("release") {
            storeFile = releaseSigningValue("RELEASE_KEYSTORE_FILE")?.let { file(it) }
            storePassword = releaseSigningValue("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = RELEASE_KEY_ALIAS
            keyPassword = releaseSigningValue("RELEASE_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.core:core-ktx:1.18.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.60.0")
}

val installGitHooks by tasks.registering {
    description = "Points git at the committed .githooks directory unless core.hooksPath is already set."
    doLast {
        val repository = rootProject.projectDir
        if (!File(repository, ".git").exists()) return@doLast
        val probe = ProcessBuilder("git", "config", "--get", "core.hooksPath")
            .directory(repository)
            .redirectErrorStream(true)
            .start()
        val configured = probe.inputStream.bufferedReader().readText().trim()
        probe.waitFor()
        if (configured.isEmpty()) {
            val set = ProcessBuilder("git", "config", "core.hooksPath", ".githooks")
                .directory(repository)
                .inheritIO()
                .start()
            check(set.waitFor() == 0) { "git config core.hooksPath .githooks failed" }
        }
    }
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(installGitHooks)
    }
}
