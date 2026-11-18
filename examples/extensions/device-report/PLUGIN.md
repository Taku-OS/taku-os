# Extension contract: device-report

Developers can implement `index.mjs` to call existing OS capabilities and compose new behavior. The root `spec/extensions.json` is the sole machine-readable source for entry points, versions, permissions, and capability declarations. This file explains the conventions without maintaining a parallel manifest.

- `run(client)` is asynchronous and returns a JSON-serializable result.
- Call the core through `client.call(method, params)`; each method must appear in the registry's capabilities.
- Failures include code/message/retryable. Preserve errors for partially failed reads; do not fabricate observations.
- Extensions currently run in the caller's trusted process. Registry checks are not a code sandbox. Android permissions depend on the actual UID, SELinux, authorized ADB, and system services.
- There is no network-exposed root shell, separate queue, or automatic app privilege elevation.

Modify this example's core logic to build trusted local extensions. Available OS methods remain defined by `methods.list`. Camera filters, notification control, and background taps require actual capability implementations and registration; declaring a name does not establish support.
