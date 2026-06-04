# PocketHost APK artifacts

Run from the repository root:

```bash
./scripts/package-android.sh release
```

Expected generated APKs:

- `pockethost-release-arm64-v8a-release.apk`
- `pockethost-release-armeabi-v7a-release.apk`
- `pockethost-release-x86-release.apk`
- `pockethost-release-x86_64-release.apk`
- `pockethost-release-universal-release.apk`

The current release build type is debug-signed for sideload testing only.
