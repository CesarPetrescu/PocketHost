#!/usr/bin/env python3
"""Stage minimal debug-signed PocketHost developer APK carriers offline.

This is an environment fallback for CI/dev containers that already have the
native daemon artifacts but cannot download the Android SDK/Build Tools needed
by Gradle. The normal path remains scripts/package-android.sh, which produces
full Android app APKs with the Compose UI and supervisor bytecode.
"""
from __future__ import annotations

import argparse
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JNI_DIR = ROOT / "android" / "app" / "src" / "main" / "jniLibs"
RELEASE_DIR = ROOT / "releases" / "apk"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
APP_ID = "dev.pockethost"
VERSION_CODE = 1
VERSION_NAME = "0.1.0"
MIN_SDK = 26
TARGET_SDK = 36

# Android framework attribute resource IDs used in the generated binary manifest.
ATTRS = {
    "name": 0x01010003,
    "versionCode": 0x0101021B,
    "versionName": 0x0101021C,
    "minSdkVersion": 0x0101020C,
    "targetSdkVersion": 0x01010270,
    "label": 0x01010001,
    "hasCode": 0x0101000C,
    "extractNativeLibs": 0x010104EA,
}

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_BOOLEAN = 0x12
NO_INDEX = 0xFFFFFFFF
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def align4(data: bytes) -> bytes:
    return data + (b"\x00" * ((4 - len(data) % 4) % 4))


def enc_len(length: int) -> bytes:
    if length < 0x80:
        return bytes([length])
    return bytes([(length >> 8) | 0x80, length & 0xFF])


class StringPool:
    def __init__(self) -> None:
        self.items: list[str] = []
        self.index: dict[str, int] = {}

    def add(self, value: str) -> int:
        if value not in self.index:
            self.index[value] = len(self.items)
            self.items.append(value)
        return self.index[value]

    def __getitem__(self, value: str) -> int:
        return self.index[value]

    def chunk(self) -> bytes:
        encoded = bytearray()
        offsets: list[int] = []
        for value in self.items:
            raw = value.encode("utf-8")
            offsets.append(len(encoded))
            encoded.extend(enc_len(len(value)))
            encoded.extend(enc_len(len(raw)))
            encoded.extend(raw)
            encoded.append(0)
        encoded = bytearray(align4(bytes(encoded)))
        header_size = 28
        strings_start = header_size + 4 * len(offsets)
        size = strings_start + len(encoded)
        out = bytearray(struct.pack("<HHI", 0x0001, header_size, size))
        out.extend(struct.pack("<IIIII", len(offsets), 0, 0x00000100, strings_start, 0))
        out.extend(struct.pack(f"<{len(offsets)}I", *offsets))
        out.extend(encoded)
        return bytes(out)


def typed_value(data_type: int, data: int) -> bytes:
    return struct.pack("<HBBI", 8, 0, data_type, data)


def attr(pool: StringPool, namespace: str | None, name: str, raw: str | None, data_type: int, data: int) -> bytes:
    ns_idx = pool[namespace] if namespace else NO_INDEX
    raw_idx = pool[raw] if raw is not None else NO_INDEX
    return struct.pack("<III", ns_idx, pool[name], raw_idx) + typed_value(data_type, data)


def node_header(chunk_type: int, size: int, line: int = 1) -> bytes:
    return struct.pack("<HHIII", chunk_type, 16, size, line, NO_INDEX)


def start_namespace(pool: StringPool) -> bytes:
    size = 24
    return node_header(0x0100, size) + struct.pack("<II", pool["android"], pool[ANDROID_NS])


def end_namespace(pool: StringPool) -> bytes:
    size = 24
    return node_header(0x0101, size) + struct.pack("<II", pool["android"], pool[ANDROID_NS])


def start_element(pool: StringPool, name: str, attrs: list[bytes]) -> bytes:
    header_size = 36
    size = header_size + 20 * len(attrs)
    body = node_header(0x0102, size)
    body += struct.pack("<IIHHHHHH", NO_INDEX, pool[name], 20, 20, len(attrs), 0, 0, 0)
    return body + b"".join(attrs)


def end_element(pool: StringPool, name: str) -> bytes:
    size = 24
    return node_header(0x0103, size) + struct.pack("<II", NO_INDEX, pool[name])


def build_manifest() -> bytes:
    pool = StringPool()
    strings = [
        "android", ANDROID_NS, "manifest", "uses-sdk", "uses-permission", "application",
        "package", APP_ID, "versionCode", str(VERSION_CODE), "versionName", VERSION_NAME,
        "minSdkVersion", str(MIN_SDK), "targetSdkVersion", str(TARGET_SDK), "name",
        "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.WAKE_LOCK", "label", "PocketHost", "hasCode", "extractNativeLibs",
    ]
    for s in strings:
        pool.add(s)

    resource_ids = [ATTRS[k] for k in ("versionCode", "versionName", "minSdkVersion", "targetSdkVersion", "name", "label", "hasCode", "extractNativeLibs")]
    res_map = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(resource_ids)) + struct.pack(f"<{len(resource_ids)}I", *resource_ids)

    chunks: list[bytes] = [start_namespace(pool)]
    chunks.append(start_element(pool, "manifest", [
        attr(pool, None, "package", APP_ID, TYPE_STRING, pool[APP_ID]),
        attr(pool, ANDROID_NS, "versionCode", str(VERSION_CODE), TYPE_INT_DEC, VERSION_CODE),
        attr(pool, ANDROID_NS, "versionName", VERSION_NAME, TYPE_STRING, pool[VERSION_NAME]),
    ]))
    chunks.append(start_element(pool, "uses-sdk", [
        attr(pool, ANDROID_NS, "minSdkVersion", str(MIN_SDK), TYPE_INT_DEC, MIN_SDK),
        attr(pool, ANDROID_NS, "targetSdkVersion", str(TARGET_SDK), TYPE_INT_DEC, TARGET_SDK),
    ]))
    chunks.append(end_element(pool, "uses-sdk"))
    permissions = [s for s in strings if s.startswith("android.permission.")]
    for permission in permissions:
        chunks.append(start_element(pool, "uses-permission", [
            attr(pool, ANDROID_NS, "name", permission, TYPE_STRING, pool[permission]),
        ]))
        chunks.append(end_element(pool, "uses-permission"))
    chunks.append(start_element(pool, "application", [
        attr(pool, ANDROID_NS, "label", "PocketHost", TYPE_STRING, pool["PocketHost"]),
        attr(pool, ANDROID_NS, "hasCode", None, TYPE_BOOLEAN, 0),
        attr(pool, ANDROID_NS, "extractNativeLibs", None, TYPE_BOOLEAN, 0xFFFFFFFF),
    ]))
    chunks.append(end_element(pool, "application"))
    chunks.append(end_element(pool, "manifest"))
    chunks.append(end_namespace(pool))

    body = pool.chunk() + res_map + b"".join(chunks)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def copy_libs(zip_out: zipfile.ZipFile, abi: str) -> None:
    abi_dir = JNI_DIR / abi
    if not abi_dir.is_dir():
        raise FileNotFoundError(f"Missing native library directory: {abi_dir}")
    libs = sorted(abi_dir.glob("lib*.so"))
    if not libs:
        raise FileNotFoundError(f"No native libraries found for {abi}: {abi_dir}")
    for lib in libs:
        zip_out.write(lib, f"lib/{abi}/{lib.name}")


def unsigned_apk(path: Path, abis: tuple[str, ...]) -> None:
    manifest = build_manifest()
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        info = zipfile.ZipInfo("AndroidManifest.xml")
        info.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(info, manifest)
        for abi in abis:
            copy_libs(zf, abi)


def ensure_debug_keystore(path: Path) -> None:
    if path.exists():
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        "keytool", "-genkeypair", "-keystore", str(path), "-storepass", "android",
        "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US",
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def sign_apk(unsigned: Path, signed: Path, keystore: Path) -> None:
    shutil.copy2(unsigned, signed)
    subprocess.run([
        "jarsigner", "-keystore", str(keystore), "-storepass", "android", "-keypass", "android",
        "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", str(signed), "androiddebugkey",
    ], check=True, stdout=subprocess.DEVNULL)


def write_readme(variant: str) -> None:
    (RELEASE_DIR / "README.md").write_text(f"""# PocketHost APK artifacts

Run from the repository root:

```bash
./scripts/package-android.sh {variant}
```

Expected staged developer APKs:

- `pockethost-{variant}-arm64-v8a-{variant}.apk`
- `pockethost-{variant}-armeabi-v7a-{variant}.apk`
- `pockethost-{variant}-x86-{variant}.apk`
- `pockethost-{variant}-x86_64-{variant}.apk`
- `pockethost-{variant}-universal-{variant}.apk`

These APKs are local, debug-signed sideload/developer artifacts only. They are
not public release artifacts and are not Google Play release artifacts.

When Gradle cannot package the full Android app because the Android SDK is
unavailable, `package-android.sh` falls back to `offline-developer-apks.py`. The
fallback APKs carry the packaged native daemon artifacts for each ABI and a
minimal installable manifest. Use a full Android SDK with platform android-36
and Build Tools 36.x to build the complete Compose supervisor application.
""")


def stage(variant: str) -> None:
    RELEASE_DIR.mkdir(parents=True, exist_ok=True)
    for old in RELEASE_DIR.glob("*.apk"):
        old.unlink()
    keystore = Path.home() / ".android" / "debug.keystore"
    ensure_debug_keystore(keystore)
    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp = Path(tmp_dir)
        for abi in ABIS:
            unsigned = tmp / f"{abi}.apk"
            output = RELEASE_DIR / f"pockethost-{variant}-{abi}-{variant}.apk"
            unsigned_apk(unsigned, (abi,))
            sign_apk(unsigned, output, keystore)
        unsigned = tmp / "universal.apk"
        output = RELEASE_DIR / f"pockethost-{variant}-universal-{variant}.apk"
        unsigned_apk(unsigned, ABIS)
        sign_apk(unsigned, output, keystore)
    write_readme(variant)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("variant", nargs="?", default="release", choices=("debug", "release"))
    args = parser.parse_args()
    stage(args.variant)
    for apk in sorted(RELEASE_DIR.glob("*.apk")):
        print(apk)
    return 0


if __name__ == "__main__":
    sys.exit(main())
