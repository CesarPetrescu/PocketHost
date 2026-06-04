# Releases

This directory is the local staging area for PocketHost build outputs.

Use the Android packaging script to rebuild split APKs:

```bash
./scripts/package-android.sh release
```

The generated files are written under `releases/apk/` and are debug-signed by
the current Gradle configuration. Treat them as developer/sideload artifacts,
not public release artifacts or Google Play deliverables.
