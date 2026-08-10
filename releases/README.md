# Releases

This directory is a local staging area for PocketHost build outputs.

It is not a production release channel. Files created here are developer
artifacts for sideload testing unless a future release process adds production
signing, release notes, verification evidence, and human approval.

Build local split APKs from the repository root:

```bash
./scripts/package-android.sh release
```

The script runs the Android Gradle build and copies generated APKs into
`releases/apk/`.

Current release limitations:

- The `release` build type is debug-signed.
- Google Play distribution is not configured.
- Device/emulator evidence must be collected separately.
- Public release claims require review of bundled native binaries, licenses,
  notices, and hashes.
