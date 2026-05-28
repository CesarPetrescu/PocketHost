# License decision

## Selected license

PocketHost code and documentation are licensed under Apache License 2.0.

SPDX identifier:

```text
Apache-2.0
```

## Why Apache-2.0

- permissive enough for personal, commercial, and enterprise use
- includes an explicit patent grant
- compatible with the Android/Kotlin/Go/Rust ecosystem style
- aligns with Apache-2.0 optional integrations such as cloudflared, Tuwunel, and Conduit when those upstream licenses are preserved
- avoids AGPL network-copyleft obligations for the PocketHost control plane
- makes Dendrite a gated decision, because the current Element Dendrite repository reports AGPL-3.0 and an additional unknown/commercial license marker

## What is covered

Covered by this repository's Apache-2.0 license:

- Kotlin Android app code
- Go daemon code
- Rust placeholder adapter code
- build scripts
- documentation
- sample configuration files that do not contain third-party content

Not automatically covered:

- Cloudflare account credentials
- user data
- Matrix homeserver binaries downloaded from upstream; Dendrite currently requires extra legal review before bundling
- cloudflared binaries downloaded from upstream
- Nextcloud or Linux-userland packages
- Android SDK/Gradle/Kotlin/Compose dependencies
- future project trademarks/logos unless explicitly licensed

## Redistribution rule for bundled third-party binaries

Before a release includes any third-party binary, add to `NOTICE`:

```text
Component:
Version:
Source:
License:
Build target:
SHA256:
Modifications:
```

Do not ship a third-party binary when its license is unknown or incompatible with the release channel.
