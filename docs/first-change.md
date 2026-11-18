# Your first change

This exercise uses the SDK to read real repository metadata, then changes one line of a filter so you can see the output change. You need only a cloned repository and Node.js 20+. You do not need `npm install`, Python, a JDK, the Android SDK, a device or a model account.

Run every command from the repository root. Practice files go in the git-ignored `out/first-change/` directory. If you have done this exercise before, keep a copy of any practice files you edited, because the next step overwrites them.

## Run the real SDK

Create `out/first-change/list-methods.mjs`.

```sh
mkdir -p out/first-change
cat > out/first-change/list-methods.mjs <<'JS'
import {TakuClient} from '../../sdk/client.mjs';

const implementation = 'core';
const methods = await new TakuClient().call('methods.list');
const ids = methods
  .filter(method => method.implementation === implementation)
  .map(method => method.id)
  .sort();

console.log(JSON.stringify({implementation, methods: ids}, null, 2));
JS
node out/first-change/list-methods.mjs
```

The command should exit `0` and print this output.

```json
{
  "implementation": "core",
  "methods": [
    "methods.list"
  ]
}
```

The call goes through [sdk/client.mjs](../sdk/client.mjs), [core/facade.mjs](../core/facade.mjs) and [core/methods.json](../core/methods.json). `new TakuClient()` has no transport, so the facade reads `methods.list` straight from the file.

## Change one line and see a different result

In `out/first-change/list-methods.mjs`, change this line:

```js
const implementation = 'core';
```

to this:

```js
const implementation = 'device-probe';
```

Run the same file again and save the result.

```sh
node out/first-change/list-methods.mjs > out/first-change/device-methods.json
cat out/first-change/device-methods.json
```

The command should exit `0` and print this output.

```json
{
  "implementation": "device-probe",
  "methods": [
    "displays.status",
    "system.info",
    "tasks.status"
  ]
}
```

You changed the client's filter on the real method list. You are done when the output changes from `methods.list` to these three IDs and the repository's implementation and metadata files are unchanged.

## Check the error without a device

This standalone command calls `system.info`, which needs a device, and confirms that the SDK throws an error code you can test for.

```sh
node --input-type=module <<'JS'
import assert from 'node:assert/strict';
import {TakuClient} from './sdk/client.mjs';

await assert.rejects(
  () => new TakuClient().call('system.info'),
  error => error.code === 'DEVICE_REQUIRED',
);
console.log('PASS: system.info without a transport returns DEVICE_REQUIRED');
JS
```

The command should exit `0` and print `PASS: system.info without a transport returns DEVICE_REQUIRED`. This shows the SDK preserves the protocol error. No current SDK method accepts non-empty parameters, and an unknown method returns `METHOD_NOT_FOUND`. See the [protocol](protocol.md) for the format.

## Where to go next

To work with a device, go to [docs.taku.ai](https://docs.taku.ai). To build the native components or the React Native UI, see the [prerequisites](getting-started.md#prerequisites). Before you submit a change, read [development](development.md), and do not commit the practice and check output under `out/`.
