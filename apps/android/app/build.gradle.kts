import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val releaseKeystore = providers.environmentVariable("MURMUR_ANDROID_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("MURMUR_ANDROID_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MURMUR_ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MURMUR_ANDROID_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val legacyBridge = providers.gradleProperty("murmurLegacyBridge").map(String::toBoolean).getOrElse(false)
require(releaseSigningValues.all { it.isNullOrBlank() } || releaseSigningValues.all { !it.isNullOrBlank() }) {
    "Set all four MURMUR_ANDROID signing variables or none of them"
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.local.murmur"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    if (releaseSigningValues.all { !it.isNullOrBlank() }) {
        signingConfigs {
            create("murmurRelease") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("murmurRelease")
    }

    defaultConfig {
        applicationId = if (legacyBridge) "dev.local.murmur" else "ie.semyon.murmur"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = if (legacyBridge) "0.1.5-bridge" else "0.1.5"
        manifestPlaceholders["migrationProviderEnabled"] = legacyBridge.toString()
        val updateManifestUrl = providers.gradleProperty("murmurUpdateManifestUrl").orNull
            ?: "https://github.com/semyonfox/murmur/releases/latest/download/android-update.json"
        require(updateManifestUrl.isEmpty() || updateManifestUrl.startsWith("https://")) {
            "murmurUpdateManifestUrl must use HTTPS"
        }
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"${updateManifestUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
