# Developer call protocol 0.1

Request: `{"protocol":"taku/0.1","type":"request","id":"req-1","method":"system.info","params":{}}`.

Success: `{"protocol":"taku/0.1","type":"response","id":"req-1","ok":true,"result":{}}`. Each method defines its own `result`.

Failure: `{"protocol":"taku/0.1","type":"response","id":"req-1","ok":false,"error":{"code":"PERMISSION_DENIED","message":"...","retryable":false}}`. The CLI prints one complete JSON document per run. Exit code 0 means success, and 1, 2 or other codes mean failure. The SDK throws `TakuError` on failure. If `device-report` partly fails, it keeps `errors` and exits non-zero.

`INVALID_REQUEST / INVALID_PARAMS / METHOD_NOT_FOUND / DEVICE_REQUIRED / INVALID_TRANSPORT / TRANSPORT_ERROR / TIMEOUT / INVALID_RESPONSE / PERMISSION_DENIED / UNSUPPORTED_PLATFORM / INVENTORY_UNAVAILABLE / EXTENSION_NOT_FOUND / CAPABILITY_NOT_DECLARED`. Retry only when `retryable:true`. Do not hide a permission error behind endless retries.

## Current methods

| Method | Input | Output | Entry point and limits |
| --- | --- | --- | --- |
| `methods.list` | `{}` | Array of the methods this repository implements | No device. Separate from the research capability list |
| `capabilities.list` | `{}` | The capability list as stored | Includes research, emulator and physical-device status. Not every listed capability is a callable method |
| `extensions.list` | `{}` | Registry array | Currently only the trusted local `device-report` |
| `system.info` | `{}` | model/manufacturer/androidRelease/apiLevel/securityPatch/abi/buildType/selinux/callerUid strings | getprop, getenforce, id. Does not read unique device identifiers |
| `displays.status` | `{}` | displayIds:number[]; background:{displayId,active} or null | cmd display. `active` requires that the dynamic ID still exists |
| `tasks.status` | `{}` | tasks:{id,state}[]; source/includesPrompts/includesResults | The existing task service `state.json`. Needs root or system UID and a readable filesystem path |

No current method accepts extra parameters. The SDK schema defines only the transport shape, and `core` validates business parameters. Android version support depends on the capability list and tested devices. These shell probes have been tested only on API 36 userdebug, so do not assume they behave the same on any stock phone.

For Android API detail, see the [API reference](https://docs.taku.ai/os/reference/).

## Events

Reserved: `{"protocol":"taku/0.1","type":"event","event":"task.state.changed","timestamp":"ISO8601","requestId":"optional","data":{}}`. The schema defines the shape, but **subscriptions, ordering, cursor recovery and deduplication are not implemented**. `tasks.status` is a snapshot query, not a live event. Events would come only from the existing task service, with no second queue.

## Type-free JS example

```js
import {TakuClient} from '../sdk/client.mjs';
const client = new TakuClient({transport:'adb',serial:'emulator-5582'});
const device = await client.call('system.info');
```

Permissions come from the real transport: the process UID, SELinux and the approved ADB connection. An extension declaration in the protocol does not grant any Android permission. After a new extension is registered, its capabilities limit which SDK methods it can call. The current trusted-process model is not a sandbox.
