plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.rnandroidhls"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.json:json:20240303")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    val ffmpegAar = file("libs/ffmpeg-kit-full.aar")
    if (ffmpegAar.exists()) {
        add("implementation", files(ffmpegAar))
        add("implementation", "com.arthenica:smart-exception-java:0.2.1")
        add("implementation", "com.arthenica:smart-exception-java9:0.2.1")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
}
