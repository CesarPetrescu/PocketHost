# APK Build and Smoke Test Runbook

## Build release APKs

Load the D: build environment, then run:

```powershell
cd android
.\gradlew.bat :app:assembleRelease --no-daemon --stacktrace
```

Stage fresh sideload APKs:

```powershell
New-Item -ItemType Directory -Path ..\releases\apk -Force | Out-Null
Copy-Item app\build\outputs\apk\release\*.apk ..\releases\apk\ -Force
```

## Check APK contents

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::OpenRead('releases\apk\app-arm64-v8a-release.apk').Entries.FullName | Select-String 'libmatrixd.so|libnextcloudd.so|libcloudflared.so|nextcloud-server-32.0.11.zip'
[IO.Compression.ZipFile]::OpenRead('releases\apk\app-x86_64-release.apk').Entries.FullName | Select-String 'libmatrixd.so|libnextcloudd.so|libcloudflared.so|nextcloud-server-32.0.11.zip'
```

`libphp.so` should only appear after a real Android PHP runtime has been staged.

## Device smoke test

```powershell
adb devices
adb install -r releases\apk\app-arm64-v8a-release.apk
adb shell monkey -p dev.pockethost 1
```

Smoke checks:

- App launches and asks for notification permissions if needed.
- Foreground supervisor notification appears after starting services.
- Web service starts on `127.0.0.1:8080` inside the device.
- Tunnel screen shows selected target and Quick Tunnel URL after cloudflared emits one.
- Matrix screen saves `tuwunel.toml` and health checks `/_matrix/client/versions` after start.
- Nextcloud screen remains disabled until PHP runtime exists, then installs server payload into app-private storage.

## x86_64 emulator smoke test

Keep AVDs on `D:`:

```powershell
$env:ANDROID_AVD_HOME='D:\AndroidBuild\avd'
avdmanager create avd -n PocketHostApi36 -k "system-images;android-36;google_apis;x86_64" --device pixel_7
emulator -avd PocketHostApi36
adb install -r releases\apk\app-x86_64-release.apk
```
