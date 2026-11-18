# Package name, version and installation location · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Authorized ADB; ordinary apps need queries or legitimate visibility; Shizuku authorization does not expand shell privileges
# Expected result: Returns the actual base.apk path and version/min/target; inspect only the intended package
# This review did not automatically execute this example; define scenario-specific success criteria first.
pm path org.agentos.apiboard
dumpsys package org.agentos.apiboard
