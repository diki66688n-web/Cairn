# Cairn

**Kernel-rooted. Script-driven. No module mounts. No SELinux policy edits.**

[English](README.md) · [简体中文](README.zh-CN.md)

Cairn is an experimental Android root and script-module manager. It combines a device-specific static kernel patch, a native userspace controller (`cnd`), and an Android manager with local module WebUI support.

## Principles

- No module filesystem overlays, bind mounts, or system-partition replacement.
- No SELinux policy-data modification and no added SELinux context types.
- Explicit UID authorization; saved grants are synchronized to an in-memory kernel whitelist.
- Installation and activation are separate: customize a staged module now, activate after reboot.

**Important distinction:** the existing root implementation bypasses selected SELinux permission decisions for root processes. “No policy edits” does **not** mean the kernel leaves every SELinux enforcement path unchanged. Root shell scripts are not sandboxed and may perform external operations; Cairn currently cannot guarantee that hostile third-party scripts respect these principles.

## Current capabilities

- Manager-controlled root authorization and revocation.
- KSU-format ZIP **subset**: `module.prop`, `customize.sh`, `post-fs-data.sh`, `service.sh`, and local `webroot/index.html`.
- Dedicated English installation console with Cairn branding and installer output.
- Module staging under `/data/adb/modules_update/`, followed by boot-time commit.
- Module disable/enable controls and reboot-required state.
- Cairn WebUI API v1: `Cairn.version()`, `Cairn.status()`, and `Cairn.readFile(relativePath)`; native consent and own-module scope, no arbitrary root-exec API.
- System-language selection plus English and Simplified Chinese override. Translation coverage is still being completed; installer-generated messages use English, while third-party script output is preserved.

## Status and limitations

This is not a universal root installer or a drop-in KernelSU replacement. Builds target the tested OnePlus Turbo 6 kernel image; hardcoded symbols and structure offsets are not portable. Do not flash an image on another device or kernel revision.

The wl5 device boot, UID authorization and whitelist have been user-verified. Later module transaction and WebUI changes have build/QEMU coverage, but not complete device acceptance. The QEMU init is a test harness, not Android init's parser. Keystore-based boot grant restoration occurs after unlock and depends on boot-broadcast delivery. `SKIPUNZIP`, complete KSU installer compatibility, crash-consistent transactions and complete module lifecycle support remain unfinished.

## Build and testing

Current build entry points: `app/build.sh`, `daemon/build.sh`, and `tools/build_wl6.py`. These still contain local development paths; reproducible public build configuration is a release blocker. Kernel input images are not supplied. Test entry points and evidence are under `test/`; logs must not be published without privacy review.

## References and acknowledgements

- [KernelSU](https://github.com/tiann/KernelSU): module ZIP conventions, installation environment and WebUI UX references. Cairn APIs are not KernelSU APIs.
- [KernelPatch](https://github.com/bmax121/KernelPatch): studied root/authorization and runtime-memory design ideas; not an endorsement or claim of shared implementation.
- [APatch](https://github.com/bmax121/APatch): installer presentation reference. Cairn uses its own banner.
- [Toybox](https://landley.net/toybox/): bundled shell, built against Android's bionic headers/runtime. Preserve Toybox's license and relevant source notices.
- Android platform tools and LLVM/GNU build tools.
- Glass navigation: KSU-derived source + miuix 0.9.4; see [UI_SOURCES.md](UI_SOURCES.md). Old unknown-origin UI binaries are no longer used.

See [LICENSING.md](LICENSING.md) and [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## License

Cairn-owned independent manager/controller sources are intended for **GPL-3.0-only**, subject to the ownership and dependency audit. Linux-kernel-derived or kernel-linked work remains subject to **GPL-2.0-only** and applicable upstream exceptions; GPLv3 does not relicense Linux. Third-party material retains its original license. No release is represented as license-cleared until the audit is complete.

## Safety

Development software can prevent boot or cause data loss. Keep a stock boot image and recovery procedure. Never publish live authorization keys, signing keys, private logs, vendor firmware images or device identifiers. Do not upload this working tree wholesale.
## AI assistance disclosure

Parts of this project were developed with AI assistance, including portions of code, documentation, debugging, and test preparation. AI assistance does not guarantee correctness, security, or license compliance, and does not replace human review and device validation. This disclosure does not change third-party attribution or applicable licenses.

## Device support and community

**Currently, the OnePlus Turbo 6 is the only device tested on real hardware. Other devices have not been adapted or validated.** Compatibility is limited to the tested kernel build, not every firmware version. Do not flash these images on other devices or unverified kernels.

We are recruiting contributors for additional device ports and real-hardware testing. Contact [diki66688n@gmail.com](mailto:diki66688n@gmail.com) with your device model and kernel version.

**Telegram channel:** https://t.me/auroraksp

## Contact

If you encounter an issue, or have questions about code attribution, licensing, or third-party rights, please contact the maintainer at [diki66688n@gmail.com](mailto:diki66688n@gmail.com). Please include relevant details so the issue can be investigated and addressed.

## Glass navigation update

KSU-derived floating navigation, drag physics and highlights now replace the old binary library. The dark navigation has user confirmation. This is not a full Compose page rewrite. See [UI_SOURCES.md](UI_SOURCES.md) for exact sources, modifications and license notices. Clean-checkout build scripts still need consolidation.
