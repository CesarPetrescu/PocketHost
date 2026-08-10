# Android Runtime Build Runbook

This runbook builds real PocketHost daemon artifacts for Android.

## Go daemons

From the repository root with the D: environment loaded:

```powershell
go test ./...
```

ARM64 Go daemon build:

```powershell
$env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='0'
foreach($cmd in 'hostd','webd','filed','ddnsd','proxyd','nextcloudd') {
  go build -trimpath -ldflags='-s -w' -o "android\app\src\main\jniLibs\arm64-v8a\lib$cmd.so" ".\go\cmd\$cmd"
}
```

x86_64 Go daemon build:

```powershell
$env:GOOS='android'; $env:GOARCH='amd64'; $env:CGO_ENABLED='1'
$env:CC='D:\AndroidSDK\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\x86_64-linux-android26-clang.cmd'
foreach($cmd in 'hostd','webd','filed','ddnsd','proxyd','nextcloudd') {
  go build -trimpath -ldflags='-s -w' -o "android\app\src\main\jniLibs\x86_64\lib$cmd.so" ".\go\cmd\$cmd"
}
```

## Tuwunel Matrix

Tuwunel `v1.7.0` source is staged at `D:\PocketHostDeps\tuwunel-v1.7.0`.

The Windows build needs Android NDK clang, Git for Windows `sh.exe`, Rust targets, and `LIBCLANG_PATH` from LLVM:

```powershell
$ndkbin='D:\AndroidSDK\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin'
$env:LIBCLANG_PATH='D:\AndroidBuild\llvm\bin'
$env:Path="D:\AndroidBuild\cargo\bin;$ndkbin;C:\Program Files\Git\usr\bin;D:\AndroidBuild\llvm\bin;$env:Path"
```

The successful local build used `--no-default-features` to avoid the upstream jemalloc/autotools failure on native Windows:

```powershell
cargo build --release --target aarch64-linux-android --bin tuwunel --no-default-features
cargo build --release --target x86_64-linux-android --bin tuwunel --no-default-features
Copy-Item D:\PocketHostDeps\tuwunel-v1.7.0\target\aarch64-linux-android\release\tuwunel android\app\src\main\jniLibs\arm64-v8a\libmatrixd.so -Force
Copy-Item D:\PocketHostDeps\tuwunel-v1.7.0\target\x86_64-linux-android\release\tuwunel android\app\src\main\jniLibs\x86_64\libmatrixd.so -Force
```

## PHP and Nextcloud

Nextcloud Server `v32.0.11` is staged as `D:\PocketHostDeps\nextcloud-v32.0.11\assets\nextcloud-server-32.0.11.zip` and packaged by Gradle from that D: asset directory.

`libphp.so` is not staged until a real Android PHP runtime is built with the required SQLite/minimal Nextcloud extensions. Use `scripts/stage-nextcloud-experimental.sh` only after such a runtime exists.

## PHP runtime packaging, updated 2026-06-04

The app now expects two PHP runtime pieces per ABI:

- Native executable staged as `android/app/src/main/jniLibs/<abi>/libphp.so`.
- Runtime asset staged as `D:\PocketHostDeps\php-android\assets\php-runtime-<abi>.zip`.

The runtime zip is extracted on-device into app-private `runtime/php/<abi>/` and must contain:

- `lib/` for PHP shared library dependencies.
- `extensions/` for loadable PHP extensions, or an `extensions.txt` manifest for built-in extensions.
- `etc/` for generated/packaged PHP config.

Use `scripts\stage-php-runtime.ps1` after the Termux-derived build produces a real PHP executable and runtime tree.
