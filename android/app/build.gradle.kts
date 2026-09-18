import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

/**
 * Resolves a build setting from, in order: environment variable, Gradle property
 * (-P or ~/.gradle/gradle.properties) and the untracked android/local.properties.
 */
fun buildSetting(propertyKey: String, envKey: String): String? =
    listOf(
        providers.environmentVariable(envKey).orNull,
        providers.gradleProperty(propertyKey).orNull,
        localProperties.getProperty(propertyKey),
    ).firstOrNull { !it.isNullOrBlank() }?.trim()

// Emulator loopback to the CampusMeal NestJS API running on the development machine.
val emulatorApiBaseUrl = "http://10.0.2.2:3000/api/v1/"
val debugApiBaseUrl = buildSetting("campusmeal.apiBaseUrl", "CAMPUSMEAL_API_BASE_URL") ?: emulatorApiBaseUrl
val releaseApiBaseUrl = buildSetting("campusmeal.releaseApiBaseUrl", "CAMPUSMEAL_RELEASE_API_BASE_URL")

android {
    namespace = "com.campusmeal.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.campusmeal.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"${releaseApiBaseUrl.orEmpty()}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // Exported Room schemas let MigrationTestHelper validate migrations in instrumented tests.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Release artifacts must point at an explicitly configured HTTPS backend; there is no fallback.
tasks.configureEach {
    if (name == "preReleaseBuild") {
        val url = releaseApiBaseUrl
        doFirst {
            check(!url.isNullOrBlank() && url.startsWith("https://") && url.endsWith("/")) {
                "Release builds require campusmeal.releaseApiBaseUrl (or CAMPUSMEAL_RELEASE_API_BASE_URL) " +
                    "set to an https:// URL ending in '/'. See android/README.md."
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.play.services.location)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
}
