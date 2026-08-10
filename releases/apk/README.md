# PocketHost APK Artifacts

This directory receives locally generated APKs from:

```bash
./scripts/package-android.sh release
```

Expected staged developer APKs:

- `pockethost-release-arm64-v8a-release.apk`
- `pockethost-release-armeabi-v7a-release.apk`
- `pockethost-release-x86-release.apk`
- `pockethost-release-x86_64-release.apk`
- `pockethost-release-universal-release.apk`

These APKs are local, debug-signed sideload/developer artifacts only. They are
not public release artifacts and are not Google Play release artifacts.

When Gradle cannot package the full Android app because the Android SDK is
unavailable, `package-android.sh` falls back to `offline-developer-apks.py`. The
fallback APKs carry the packaged native daemon artifacts for each ABI and a
minimal installable manifest. Use a full Android SDK with platform android-36
and Build Tools 36.x to build the complete Compose supervisor application.
