# Windows D: Dev Setup Runbook

PocketHost build caches and heavyweight SDKs should live on `D:`.

## Required locations

- Go: `D:\AndroidBuild\go`
- Android SDK: `D:\AndroidSDK`
- Android NDK: `D:\AndroidSDK\ndk\28.2.13676358`
- Gradle cache: `D:\AndroidBuild\gradle-home`
- Go caches: `D:\AndroidBuild\gocache`, `D:\AndroidBuild\go\pkg\mod`
- Rust/Cargo: `D:\AndroidBuild\rustup`, `D:\AndroidBuild\cargo`
- Runtime source deps: `D:\PocketHostDeps`
- Build outputs and AVDs: `D:\PocketHostBuild`, `D:\AndroidBuild\avd`

## PowerShell environment

```powershell
$env:JAVA_HOME='D:\AndroidBuild\jdk-21'
$env:ANDROID_HOME='D:\AndroidSDK'
$env:ANDROID_SDK_ROOT='D:\AndroidSDK'
$env:ANDROID_NDK_ROOT='D:\AndroidSDK\ndk\28.2.13676358'
$env:ANDROID_AVD_HOME='D:\AndroidBuild\avd'
$env:GRADLE_USER_HOME='D:\AndroidBuild\gradle-home'
$env:GOMODCACHE='D:\AndroidBuild\go\pkg\mod'
$env:GOCACHE='D:\AndroidBuild\gocache'
$env:CARGO_HOME='D:\AndroidBuild\cargo'
$env:RUSTUP_HOME='D:\AndroidBuild\rustup'
$env:Path="D:\AndroidBuild\go\bin;D:\AndroidBuild\cargo\bin;D:\AndroidBuild\jdk-21\bin;D:\AndroidSDK\cmdline-tools\latest\bin;D:\AndroidSDK\platform-tools;$env:Path"
```

## Installed SDK packages

```powershell
sdkmanager --sdk_root=D:\AndroidSDK "platform-tools" "platforms;android-36" "build-tools;36.1.0" "ndk;28.2.13676358" "cmake;3.22.1" "emulator" "system-images;android-36;google_apis;x86_64"
```

If `sdkmanager` warns about duplicate command-line tools, keep `D:\AndroidSDK\cmdline-tools\latest` as the active path and remove stale duplicate folders only when no build is running.
