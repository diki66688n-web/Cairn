# Licensing plan and third-party notices

This document is a scope declaration and audit checklist, not a blanket relicense of third-party work.

| Component | Intended / upstream license | Status |
|---|---|---|
| Independently authored Cairn Android manager and userspace controller | GPL-3.0-only | Owner-approved choice; provenance audit pending |
| Linux kernel and Linux-derived / linked patch portions | GPL-2.0-only, upstream exceptions where applicable | Cannot be relicensed GPLv3; kernel source obligations must be satisfied for binary distribution |
| Standalone patch-generation scripts | GPL-3.0-only if independently authored | Embedded kernel-derived material requires separate classification |
| Toybox shell | Upstream permissive license (0BSD-style text), per-file notices | Local LICENSE available; preserve exact notices and verify included files |
| LiquidGlass jar/dex | UNKNOWN | Block public distribution until origin/version/license and source requirements verified |
| Toolchains / Android platform dependencies | Respective upstream licenses | Build-time use does not grant blanket redistribution rights |
| Reference repositories | Respective original licenses | Do not copy into release tree or relicense |

Authoritative GPL texts should be placed in LICENSES/GPL-3.0-only.txt and LICENSES/GPL-2.0-only.txt. Source files need scope-appropriate SPDX identifiers after provenance classification, not indiscriminate headers. Preserve all upstream copyright notices.

GitHub can display multiple licenses. Do not place a single GPLv3 notice over the full tree and imply Linux or GPLv2-only material was relicensed. Mere discussion of a reference project is not proof of code derivation; actual reused source must be traced and licensed accordingly.

Release blockers: verify ownership/contributions, LiquidGlass origin, Toybox notices, kernel corresponding-source availability and modifications, all bundled binary distribution terms, private firmware exclusion, and compatibility between combined components. This plan is not a legal opinion or certification of compliance.
## Adopted V1.0.1 kernel-builder file scope

- `source/kernel-builder/payload.S`: GPL-2.0-only.
- `source/kernel-builder/src/main.rs`: GPL-3.0-only, independent host-side builder.
- Cargo package metadata uses `GPL-3.0-only AND GPL-2.0-only` because the package contains both separately licensed components. This is not an OR/dual-license offer and does not assert the licenses can be combined into a single linked work.
- Applicable texts are in `LICENSES/`. Per-file notices govern; third-party rights and corresponding-source obligations remain unchanged.
- Historical files under `archive/` are preserved, not relicensed. APP/userspace-wide provenance review remains pending; this update only applies the confirmed kernel-builder split.

## Attribution and licensing enquiries

For concerns about source attribution, licensing, or third-party rights, please contact [diki66688n@gmail.com](mailto:diki66688n@gmail.com) with the relevant files and details. This contact notice does not replace any applicable license or compliance obligation.
