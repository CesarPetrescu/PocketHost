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

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            // Daemons are launched by name from nativeLibraryDir, so resource/code
            // shrinking is left off until keep rules are verified on-device.
            // Debug-signed for now: installable for testing, not a Play release.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
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
