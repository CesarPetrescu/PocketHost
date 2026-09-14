import com.android.build.api.variant.FilterConfiguration.FilterType.ABI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.pockethost"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pockethost"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Ship one APK per architecture (plus a universal APK for sideloading/emulators)
    // so a phone only downloads the daemons for its own ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // A real release key, supplied by CI or a local developer through the
    // environment. When it is absent the release build falls back to the debug
    // key as before, so nothing about the local workflow changes — but a build
    // signed that way is not distributable: every machine mints a different
    // debug key, so no two such APKs are upgrade-compatible.
    val releaseKeystore = System.getenv("POCKETHOST_KEYSTORE")
        ?.takeIf { it.isNotBlank() && file(it).exists() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("POCKETHOST_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POCKETHOST_KEY_ALIAS")
                keyPassword = System.getenv("POCKETHOST_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            // Daemons are launched by name from nativeLibraryDir, so resource/code
            // shrinking is left off until keep rules are verified on-device.
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        abortOnError = false
    }

    packaging {
        jniLibs {
            // Required because the app executes daemon artifacts from nativeLibraryDir.
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            // Large runtime payloads (PHP-for-Android runtime zips, Nextcloud server zip)
            // are bundled as assets but kept out of git. Their location is configurable so
            // each machine can point at its own staging dir:
            //   -PpocketHostAssetsDir=/path  (gradle property)  OR  POCKETHOST_ASSETS_DIR env
            // Default: <project>/deps/assets (gitignored).
            val extraAssets = (project.findProperty("pocketHostAssetsDir") as String?)
                ?: System.getenv("POCKETHOST_ASSETS_DIR")
                ?: "${rootDir}/deps/assets"
            assets.srcDirs("src/main/assets", extraAssets)
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Every split APK carried the same versionCode, which makes them mutually
// unpublishable: a store keeps one artifact per versionCode, and a device has
// no upgrade ordering between them. Offset each ABI into its own band, leaving
// the universal APK on the base code so it stays the lowest-priority fallback.
//
// Ordering matters: a 64-bit device that can run several of these picks the
// highest versionCode it is compatible with, so arm64-v8a and x86_64 sit above
// their 32-bit counterparts.
val abiVersionBands = mapOf(
    "armeabi-v7a" to 1,
    "x86" to 2,
    "arm64-v8a" to 3,
    "x86_64" to 4,
)

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == ABI }?.identifier
            val base = output.versionCode.orNull ?: 1
            if (abi != null) {
                output.versionCode.set(abiVersionBands.getValue(abi) * 1000 + base)
            }
        }
    }
}
