# Memory and disk overview · Verified on an emulator (within the cited evidence)
# Environment: Root shell on the phone
# Prerequisites: Ordinary apps use ActivityManager.MemoryInfo / StatFs; the example uses an authorized shell
# Expected result: Returns MemTotal/MemAvailable and data-partition capacity; this review's probe is saved
# This review did not automatically execute this example; define scenario-specific success criteria first.
cat /proc/meminfo
df -h /data
