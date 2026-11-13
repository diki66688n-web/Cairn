# Security and disclosure

Contact: diki66688n@gmail.com. Do not include private keys, authorization secrets or personal logs in public issues. Report sensitive findings privately.

Root scripts (including customize.sh) are NOT sandboxed. No mounts/policy-edit APIs are supplied, but scripts can call external root tools. WebUI file checks have known race limitations. Boot grant restoration depends on post-unlock broadcast delivery. Keystore fallback can store a key in APP-private preferences, and hardware-backed storage is not guaranteed on every device. No claim of invisibility, universal compatibility or production hardening is made.

The repository has zero authorization placeholders and no binary releases. Signing credentials must be supplied independently and never committed. A development signing-password string existed in early private history; no signing keystore was uploaded. Rotate old signing passwords before production use. The GitHub PAT supplied in chat must be revoked independently; it is not in this repository.
