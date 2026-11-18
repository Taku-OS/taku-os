# Home cannot access the task directory

[Back to pitfalls](README.md) · [Shared environment reference](emulator-reference.md)

**Environment:** AOSP userdebug development emulator, with Home running as system UID 1000

**Symptom:** On first use, Home cannot access the task directory.

**Cause:** The task service created the app's missing `files` parent directory as root with mode 0700, while Home runs as system UID 1000.

**Verified fix:** Restore this app's parent directory to `system:system` / 0700 and restore the file label. No product source changes were needed.

1. Check the owner and permissions of the `/data/user/0/org.agentos.assistant/files` parent directory. The bad state seen here was `root:root / 0700`.
2. In a root shell on the development emulator, restore the owner, permissions and file label of this parent directory only.

```bash
chown system:system /data/user/0/org.agentos.assistant/files
chmod 0700 /data/user/0/org.agentos.assistant/files
restorecon /data/user/0/org.agentos.assistant/files
ls -ldZ /data/user/0/org.agentos.assistant/files
```

**Result:** The directory becomes `system:system / 0700` with the label `u:object_r:system_app_data_file:s0`. Home can then access the task directory and complete a conversation.
