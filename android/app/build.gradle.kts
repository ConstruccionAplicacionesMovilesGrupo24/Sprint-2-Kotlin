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
val debugApplicationIdSuffix = buildSetting("campusmeal.debugApplicationIdSuffix", "CAMPUSMEAL_DEBUG_APPLICATION_ID_SUFFIX")

// Release signing (S3-13). The keystore lives outside the repository; only its path and passwords are configured.
val releaseStoreFile = buildSetting("campusmeal.release.storeFile", "CAMPUSMEAL_RELEASE_STORE_FILE")
val releaseStorePassword = buildSetting("campusmeal.release.storePassword", "CAMPUSMEAL_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = buildSetting("campusmeal.release.keyAlias", "CAMPUSMEAL_RELEASE_KEY_ALIAS")
val releaseKeyPassword = buildSetting("campusmeal.release.keyPassword", "CAMPUSMEAL_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).none { it == null }

android {
    namespace = "com.campusmeal.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.campusmeal.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
            // Opt-in (unset by default): lets a debug build, with its crash-injection tools, install next to
            // the distributed app on a physical phone without replacing it or reading its data.
            debugApplicationIdSuffix?.let { applicationIdSuffix = it }
        }
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "API_BASE_URL", "\"${releaseApiBaseUrl.orEmpty()}\"")
        }
        // S4-02: what is measured. Derived from release (not debuggable, same minification), but readable
        // by the profilers and signed with the local debug key, so every member can build it without the
        // release keystore. It installs next to the distributed app and never touches its data.
        create("profile") {
            initWith(getByName("release"))
            isProfileable = true
            applicationIdSuffix = ".profile"
            versionNameSuffix = "-profile"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
// They must also be signed: an unsigned APK cannot be installed from Firebase App Distribution.
// The profile build follows the same backend rule; it is signed with the debug key instead.
tasks.configureEach {
    if (name == "preReleaseBuild" || name == "preProfileBuild") {
        val url = releaseApiBaseUrl
        val signed = releaseSigningConfigured || name == "preProfileBuild"
        doFirst {
            check(!url.isNullOrBlank() && url.startsWith("https://") && url.endsWith("/")) {
                "Release builds require campusmeal.releaseApiBaseUrl (or CAMPUSMEAL_RELEASE_API_BASE_URL) " +
                    "set to an https:// URL ending in '/'. See README.md at the repository root."
            }
            check(signed) {
                "Release builds require campusmeal.release.storeFile, storePassword, keyAlias and keyPassword " +
                    "(or the CAMPUSMEAL_RELEASE_* variables). See android/docs/SPRINT3_RELEASE.md."
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
}
