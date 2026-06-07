# PocketHost APK Artifacts

This directory receives locally generated APKs from:

```bash
./scripts/package-android.sh release
```

Expected split outputs:

- `pockethost-release-arm64-v8a-release.apk`
- `pockethost-release-armeabi-v7a-release.apk`
- `pockethost-release-x86-release.apk`
- `pockethost-release-x86_64-release.apk`
- `pockethost-release-universal-release.apk`

These APKs are for developer sideload testing. The current `release` build type
uses the debug signing configuration and is not suitable for public app-store
distribution.

Before treating any APK here as a candidate release, collect:

- `./scripts/ci-local.sh` output.
- Android build output.
- Install evidence on a real device or emulator.
- Foreground notification evidence.
- Daemon start/stop evidence.
- Successful local `/health` probes.
- Confirmation that no secrets are bundled in configs, assets, logs, or
  screenshots.
