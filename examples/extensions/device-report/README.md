# device-report extension

The SDK queries `system.info`, `displays.status`, and `tasks.status` and reports observation times, per-method results/errors, and scope limits. It contains no hardcoded emulator results or model mocks, submits no tasks, and reads no prompt/result bodies or account data.

```sh
node sdk/cli/taku.mjs extension device-report --transport adb --serial emulator-5582
```

Call chain: CLI → `spec/extensions.json` registry → `run(scopedClient)` → SDK/facade → ADB Android shell → shared-source probe → actual JSON. Disabled ADB or insufficient permissions must produce errors, not sample data.

To add an extension, export `run(client)` from `examples/extensions/<id>/index.mjs` and declare its methods in the registry. Implement arbitrary core logic rather than only changing configuration. The executable OS surface currently comprises three read-only probes; additional system calls require implementation and permission acceptance first.
