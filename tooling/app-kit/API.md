# Application capability interfaces

Actually exercised on emulator-5582 on 2026-10-09.

Apps freely implement `App.jsx` and `api.mjs`. The latter provides business functions and parameter/result schemas; the former calls those functions to render UI. The Node background runtime continues serving requests after the UI closes.

## On-phone CLI

Entry point: `/data/local/agentos/light-app-lab/free-app-kit/mini-app`.

```sh
mini-app list
mini-app describe board
mini-app call board tasks.add '{"text":"Prepare a weekend trip","priority":"高"}' --key trip-task-1
mini-app call board tasks.setCompleted '{"taskId":"RETURNED_TASK_ID","completed":true}'
mini-app call board tasks.list '{}'
mini-app call converter length.convert '{"value":3,"from":"ft","to":"m"}'
mini-app openapi
```

The existing board's `priority` enum uses the legacy value `高` (high); it is an API value, not translated display copy. This experiment uses `mini-app` by absolute path; it is not installed on system PATH. The CLI returns JSON and nonzero exit status on errors.

## HTTP and discovery

The service binds the phone's `127.0.0.1:8765`.

| Request | Purpose |
| --- | --- |
| `GET /health` | Background service status |
| `GET /v1/apps` | Apps and methods with published interfaces |
| `GET /v1/apps/board` | One app's method descriptions and parameter/result schemas |
| `POST /v1/apps/board/call` | Call an app business function |
| `GET /v1/openapi.json` | OpenAPI document |

Example request:

```json
{"method":"tasks.add","params":{"text":"Prepare a weekend trip","priority":"高"},"idempotencyKey":"trip-task-1"}
```

Result envelopes include `ok`, `app`, `method`, `result`, `revision`, `replayed`, and service execution duration. Failures include an error code, message, and validation details.

Host access through ADB forwarding was verified:

```sh
adb -s emulator-5582 forward --no-rebind tcp:8765 tcp:8765
curl http://127.0.0.1:8765/v1/apps
```

The service does not bind a public address. It is currently an independent root Node process in the experimental environment, not integrated into system_server or boot startup.

## Apps expose their own methods

```js
// apps/myapp/api.mjs
export default function createApp({store,notify,error}) {
  return {
    title: 'My app',
    version: '1',
    methods: {
      'notes.add': {
        description: 'Save a note',
        mutates: true,
        paramsSchema: {
          type: 'object',
          properties: {text: {type: 'string', minLength: 1}},
          required: ['text'],
          additionalProperties: false
        },
        handler({text}) {
          const data = store.update(state => {
            state.notes ||= [];
            state.notes.push(text);
          });
          return {count: data.notes.length};
        }
      }
    }
  };
}
```

This is full JavaScript business code; apps define method names and logic. `store.read()` reads the app's data; `store.update(fn)` writes atomically and increments revision. Transaction callbacks must be synchronous; asynchronous business functions may await work before starting a transaction. `notify(title,body)` publishes and reads back a system notification record.

Changing the `api.mjs` entry file reloads the module on the next call without host recompilation or service restart. Dependency-graph hot reload is unimplemented. Parameter schemas validate before calls; result schemas currently provide discovery/documentation without result validation.

## UI calls the same functions

```jsx
import {phone,useAppApiState} from './ui.jsx';

const {state,error,reload} = useAppApiState('tasks.list');
await phone.call('tasks.add', {text:'Prepare a weekend trip',priority:'高'});
await reload();
```

UI, CLI, and HTTP share one `api.mjs`, without duplicating conversion, task mutation, or statistics algorithms. The state hook polls every 750ms and actually refreshes external changes. Event push is unimplemented.

An API task-board APK (70,452 bytes) was exported and installed. After tapping Complete, HTTP readback confirmed shared-state changes, then HTTP restored them. The independent blank shell also includes the API client and records the app API ID during export. APKs using these interfaces depend on the Agent OS background runtime; ordinary `phone.read/save/notify` and local-JS-only apps can still run independently.

## Verified

- With UI closed, on-phone Codex discovered interfaces, added tasks, marked completion, and queried results entirely through CLI, without screen input.
- Codex converted 3 feet to a formatted 0.9144 meters.
- A Mac HTTP client added, queried, and deleted tasks; invalid parameters, unknown methods, and duplicate-key conflicts returned appropriate errors.
- Repeating an addition created one task. Receipts retain the latest 100 completed calls; this is not strict exactly-once execution across a process crash during business execution.
- While UI was open, an external addition automatically changed the count from 5 to 6. Codex-created/completed tasks were actually visible.
- Six tasks survived service and emulator restarts. Reusing the original addition key returned the same task ID and `replayed:true`, without increasing the count.
- Notifications require a registered Android package identity. The original root `cmd notification` exited successfully but failed delivery. It now uses system Shell identity and returns `verified: true` only after reading back the notification body.

Evidence: `evidence/api/`. Service management: `sh api/service.sh start|stop|status`. Task board, converter, and quick notes currently expose APIs; the reaction game remains UI-only logic.

Runtime libraries are in `/data/local/agentos/light-app-lab/native/lib`, without reliance on an uninstalled Termux package directory. The backend executes trusted app business code as root; it is not an app sandbox or permission-isolation implementation.

## New-app generation experiment

`generate.sh notes api/notes-prompt.txt` generated full `App.jsx` and `api.mjs` on the phone, taking 68.55 seconds to launch preview, including 60ms for build/deployment. Quick Notes defined `notes.add` and `notes.list`, independently implementing storage, keyword filtering, and Unicode code-point counting. An externally written note appeared in the UI. An initially overescaped regex was fixed by checking blank text inside the business function before acceptance passed. Repair/test time is excluded from the 68.55 seconds. Initial/final source, generation logs, and results were retained.
