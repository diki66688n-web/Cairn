# Glass UI source attribution

Cairn navigation adapts KernelSU at commit **dbb73665d103da40c7ecbd21ba3b5ac76eb95784**:
https://github.com/tiann/KernelSU/tree/dbb73665d103da40c7ecbd21ba3b5ac76eb95784/manager/app/src/main/java/me/weishu/kernelsu/ui/component

Files used:
- FloatingBottomBar.kt
- miuix/animation/DampedDragAnimation.kt
- miuix/animation/InteractiveHighlight.kt
- miuix/modifier/DragGestureInspector.kt
- liquid/CombinedBackdrop.kt, InnerShadow.kt, Lens.kt, Vibrancy.kt

Upstream file notices identify liquid helpers as adapted from **Kyant0/AndroidLiquidGlass (Apache-2.0)** and mirrored from compose-miuix-ui examples. FloatingBottomBar identifies the compose-miuix-ui IosLiquidGlassNavigationBar example (Apache-2.0). Original per-file notices are retained; KernelSU's project GPLv3 notice is also provided, without erasing file-specific notices.

Dependencies: **compose-miuix-ui/miuix 0.9.4** (miuix-ui-android, miuix-blur-android, miuix-shader-android), Apache-2.0; AndroidX Compose and AndroidX dependencies under their upstream licenses. Kotlin runtime retains upstream licensing. Thanks to KernelSU, compose-miuix-ui and Kyant0 for their work. No endorsement is implied.

Cairn modifications: namespace/import adaptation; theme lookup adaptation; fixed dark navigation color scheme consistent with current Cairn pages; Java-compatible callback host; Java page bitmap sampling bridge. Upstream physics, gesture and effect implementation retained. Cairn's host and business code remain separate. Existing self-drawn Java page cards are NOT claimed to be KSU glass.

Reference license snapshots: KernelSU pinned commit LICENSE; miuix main LICENSE fetched during migration; AndroidLiquidGlass kmp commit eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5 LICENSE. The latter snapshots are not claims of exact historical file provenance beyond the retained upstream file statements. Full dependency/NOTICE audit remains needed before claiming complete redistribution compliance.

Old unknown-origin liquidglass.jar and LiquidGlass.dex are removed from the active pipeline. This does not assert the entire app has been migrated to Compose. User confirmed the final dark navigation rendering works; broader lifecycle/security/UI tests remain incomplete.
