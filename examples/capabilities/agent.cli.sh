# On-phone Codex CLI and low-level shell · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: CLI is preinstalled; authentication resides only in device data; Android /system/bin/sh is mksh, not GNU Bash; never package authentication into the image
# Expected result: Outputs the version; model success requires actual task and command results, not just a process exit code
# This review did not automatically execute this example; define scenario-specific success criteria first.
/system_ext/bin/codex --version
# This new-task example calls a model; choose an explicit test scenario
/system_ext/bin/codex exec --skip-git-repo-check -C /data/local/agentos/work "Read and report the Android system version without operating apps" </dev/null
