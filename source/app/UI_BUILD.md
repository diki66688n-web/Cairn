# Compose UI build status
The Java manager uses ComponentActivity and a Java adapter to the Kotlin KSU-derived navigation. Old LiquidGlass.jar/dex are no longer used.

Tested local pipeline lives in the private ui-migration workspace. build-tools contains the corresponding reference scripts, still needing path/layout parameterization and a complete pinned dependency resolver before clean-checkout builds. They are NOT yet a one-command portable build. Required toolchain: Kotlin 2.4.20 + Compose compiler plugin, Compose Android 1.12.1, miuix-ui/blur/shader Android 0.9.4, AndroidX runtime dependencies including poolingcontainer and core-viewtree. Native binaries must be independently built. Never restore old precompiled LiquidGlass dex to work around missing dependencies.

The navigation uses KSU source components. The current bridge samples Java page content into a bitmap at 100ms intervals, then applies the Compose backdrop effects; it is not a full Compose page migration. Existing Java cards remain self-drawn.
