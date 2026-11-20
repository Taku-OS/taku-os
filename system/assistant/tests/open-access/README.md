# Open-access privilege boundary regression

`AgentOpenAccessPolicy` selects the required scope before the Provider parses the
request, touches state, or clears Binder identity. The entire `manageAssistant`
endpoint requires root; inner operations cannot downgrade that requirement.
Sensitive families use explicit read exceptions, so adding a new mutation does not
silently give it `taku.control` access. Draft/option writes are protected too because
they can change a later submission made through the GUI.

## Host regression

Run `bash system/assistant/test-system-ai-host.sh`. `AppPermissionPolicyTest` uses
the production permission store and scope selector. A control-only app receives
`PERMISSION_DENIED` for sensitive methods in manual, auto and yolo modes. Read and
reaction calls keep control access, and an explicit manual root grant allows a
sensitive operation. Workspace restrictions and secret-request checks are unchanged.

## Android Binder regression

Use only a disposable emulator with the matching development platform certificate.
Do not change permission modes on a user's working device. Build the backend with
`bash system/assistant/build.sh` and probes with
`bash system/assistant/build-open-access-probes.sh`.

Install both probe APKs from `out/open-access-probes/`. `control.apk` has a fresh,
non-platform signer and runs as its own ordinary UID. `gateway.apk` is platform
signed and instruments the actual `org.agentos.agent` package: the production
signature and package-name checks remain enabled. The probes do not weaken them.

With the serial explicitly selected:

```sh
adb -s TEST_SERIAL shell am instrument -w -e mode setup \
  org.agentos.permissionprobe.gateway/org.agentos.permissionprobe.OpenAccessProbe
adb -s TEST_SERIAL shell am instrument -w -e mode control \
  org.agentos.permissionprobe.control/org.agentos.permissionprobe.OpenAccessProbe
adb -s TEST_SERIAL shell am instrument -w -e mode gateway \
  org.agentos.permissionprobe.gateway/org.agentos.permissionprobe.OpenAccessProbe
```

`setup` uses the protected GUI path to select yolo with `allowRootInYolo=false`.
The control probe first confirms an actual read succeeds, then verifies the unsigned
caller cannot enter the signature gateway and receives `PERMISSION_DENIED` for
nested management operations and sensitive top-level calls. Malformed/missing
parameters prevent an accidental mutation if the permission guard regresses.
The gateway probe writes a draft through the GUI backend, reads the same text through
open access, and restores the original draft. It sends no model request.

On 2026-11-20, an independent empty-data ARM64 Android 17 emulator (`emulator-5592`)
using build `20261120140000` reproduced the old Provider failure: the control app's
nested `plugin/install` reached validation and returned `INVALID_ARGUMENT` instead
of `PERMISSION_DENIED`. Installing the rebuilt Assistant made all 28 control-probe
checks pass; the real Agent UID passed all five gateway/shared-draft checks. The
shell UID could also read, write, read back and restore the same draft. This is
component-level verification; the earlier full image predates this security fix.

The 28 control and five gateway checks were repeated successfully after installing
both APKs rebuilt from `c78db819bc307628bd39545d7b1f3ce656e9f1bc`, which includes
the latest main merge and preserves this permission fix as `53dae94a`. The published
Agent APK is locked to those 500 source inputs; its SHA-256 is
`e4af55a12e944e51c43bc7da4aedfdaad3f89fc70551ccdbb0b84d7584a83a66`.
This remains a component upgrade on the earlier emulator image, not a new ROM build.
