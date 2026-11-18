# System diagnostics and performance tracing · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Developer-authorized ADB; limit target services and test logs rather than collecting personal content in bulk
# Expected result: Reads the target package and broker service; Perfetto needs separate trace configuration
# This review did not automatically execute this example; define scenario-specific success criteria first.
dumpsys package org.agentos.apiboard
getprop init.svc.agentos_codex
