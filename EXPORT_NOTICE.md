# Public source preview — V1.0.1

This is an experimental, sanitized source preview, NOT a ready-to-flash release. Authorization constants are zero placeholders. Do not build/flash without replacing them through a secure provisioning design. Build paths and missing dependencies still require work.

No firmware, APKs, signing keys, private logs or unverified third-party UI binaries are included. LiquidGlass binaries are intentionally excluded pending provenance/license audit; APP build is therefore incomplete. Kernel binary distribution and complete corresponding-source requirements require separate review. Existing security/lifecycle limitations are documented in README and RELEASE_CHECKLIST.

Only OnePlus Turbo 6 has real-device testing; other devices are not adapted. AI-assisted work is disclosed. Third-party notices and contact details are in the bilingual README.

Historical commits contained a development keystore password string but no keystore/private key. Treat that old password as exposed and never reuse it. The current build uses environment-provided signing credentials. This source preview is not a production security certification.
