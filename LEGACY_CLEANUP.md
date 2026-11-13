# Legacy cleanup — V1.0.1

Removed unused socket clients (source/client), dormant socket request execution/response handlers in cnd, vu/pending/wire-protocol declarations, temporary DebugTerminal Activity and settings button. Shared CLI list/status formatting helpers were retained because current commands use them. Current cnd binary is byte-identical before/after cleanup (SHA256 3c2b7b205ddb4d0a7c649e166bf9d7eab1627f1ffc878c602729d658e1c04766). APP compiles and signs successfully; debug class is no longer packaged. Kernel payload unchanged. No claim of new device UI testing.

Historical source is preserved in the private local archive, not this repository. Generic syscall declarations or diagnostic pidof output were not blindly deleted. This remains a sanitized review snapshot: credentials, firmware and uncleared binary dependencies are omitted.
