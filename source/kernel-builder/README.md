# Cairn kernel builder — V1.0.1

## License boundaries

This directory contains two separately licensed components:

| Files | SPDX license |
|---|---|
| `src/main.rs` — independent userspace Rust build tool | `GPL-3.0-only` |
| `payload.S` — kernel-side AArch64 payload | `GPL-2.0-only` |

Cargo's package expression `GPL-3.0-only AND GPL-2.0-only` records the mixed contents, not a choice of licenses and not a blanket license for each file. Running the host program to assemble/patch a kernel does not relicense the kernel under GPLv3. No GPL exception is invented by this notice. Historical and third-party material retain their original notices.

Full license texts: `../LICENSES/GPL-3.0-only.txt` and `../LICENSES/GPL-2.0-only.txt`. See `../../LICENSING.md` for project scope and unresolved redistribution audits. Binary-kernel distribution requires satisfying the applicable GPLv2 corresponding-source conditions; this tool alone is not a substitute for the kernel's complete corresponding source.

## Build

Requires Rust, GNU AArch64 binutils and sha1sum. No Python at build time.

```sh
cargo test --offline
cargo run --offline --release -- STOCK_KERNEL STOCK_BOOT OUTPUT_DIR
```

The builder verifies the exact supported kernel fingerprint and hook instructions. It is device/kernel-specific, not a generic patcher. AVB is not re-signed. The original Linux version string is preserved; V1.0.1 identifies the Cairn patch release.