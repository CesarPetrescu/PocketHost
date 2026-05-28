# Nextcloud module contract

PocketHost does not implement Nextcloud natively. Nextcloud requires a Linux-style PHP, web server, database, background-job, app, and upgrade runtime. The Android app only owns an optional isolated launcher slot.

## Android service slot

Binary:

```text
android/app/src/main/jniLibs/arm64-v8a/libnextcloudd.so
```

Launch contract:

```bash
libnextcloudd.so --addr 127.0.0.1:8081 --data-dir "$APP_FILES/data/nextcloud"
```

Health contract:

```text
GET /status.php
```

The launcher must bind to loopback by default. Public routing must go through an explicit tunnel route such as:

```text
nextcloud.example.com -> http://127.0.0.1:8081
```

## Isolation rules

- Keep Nextcloud data under `files/data/nextcloud`.
- Keep Nextcloud database ownership inside the module.
- Do not share the PocketHost app SQLite database with Nextcloud.
- Do not commit admin passwords, database passwords, salts, tunnel credentials, app passwords, backups, or generated config.
- Do not migrate or delete user data without a human gate and a tested backup path.
- Do not claim production Nextcloud support until install, login, upload, background jobs, backup, restore, and upgrade have all been verified.

## Required evidence before enabling

```text
Selected Nextcloud/runtime versions:
Launcher binary source and license:
PHP/web/database/runtime layout:
Fresh data directory install:
Login test:
Upload/download test:
/status.php response:
Background jobs evidence:
Backup path:
Restore test:
Tunnel route evidence:
Known maintenance gaps:
Rollback:
```
