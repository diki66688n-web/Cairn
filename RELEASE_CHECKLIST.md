# GitHub release checklist

Do not upload this development directory wholesale. Publish a reviewed export, not live device state.

- [ ] Replace embedded live authorization keys with a configuration/provisioning mechanism; secrets occur in build scripts and tests, not just keys/.
- [ ] Remove APK signing private keys/keystores, user identifiers, auth databases and logs. Review Git history as well as current files.
- [ ] Exclude stock and patched boot/kernel firmware images, baseline runtime policies, vendor blobs and reference-repository copies until redistribution terms are established.
- [ ] Verify LiquidGlass origin/version/license; provide required notices and corresponding source as applicable.
- [ ] Include Toybox LICENSE and per-source notices; record exact source revision/build configuration.
- [ ] Classify kernel-derived code GPL-2.0-only and independent userspace GPL-3.0-only; ensure full corresponding-source obligations for any kernel binaries distributed.
- [ ] Replace absolute workspace/toolchain paths with documented build parameters.
- [ ] Test clean checkout build and publish checksums/reproducible tool versions.
- [ ] Finish English/Chinese string coverage and device language-switch validation.
- [ ] Device-validate installer transcript, staging/updates, boot recovery and WebUI permission checks.
- [ ] Review boot-stage ordering, update locking, crash recovery, uninstall and SKIPUNZIP limitations.
- [ ] Document threat model: root scripts are not sandboxed. No claim of hiding root, universal compatibility, complete KSU compatibility or untouched SELinux enforcement.

Prepared public description:
> Experimental Android root and script-module manager. Reboot-staged modules, native WebUI APIs, no module mounts and no SELinux policy-data edits.

No GitHub repository has been created or uploaded by this preparation.