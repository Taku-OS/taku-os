# System AI settings: current data contract and integration status

This describes the current, interim data layer for system AI settings.

## Current values

All four values are JSON strings in `Settings.Global`; a `settings put` replaces
the **entire value**, not an individual entry. Read the latest value before an
edit and preserve unrelated entries. The task service projects them to the private
Magpie `providers.json` and retries a projection if the values change during
its snapshot checks.

| Key | Shape | Meaning |
| --- | --- | --- |
| `taku_magpie_providers` | array of provider objects | Public provider configuration. `id` identifies the provider; optional `credentialRef` references the private credential store. |
| `taku_magpie_models` | object keyed by provider ID | Selected model arrays, projected into the corresponding provider's `models`. |
| `taku_magpie_groups` | array of group objects | Magpie routing groups, with `id`, `name`, `members` and `routing`. |
| `taku_magpie_scene_bindings` | object keyed by scene ID | Values are `group/<id>`. Built-in scenes include `assistant`, `jev`, `memory.text`, `ime.vision` and `screen`; preserve other app-specific keys. |

Credentials, headers, tokens, URL userinfo and URL query parameters must never
be put in Settings. The current `credentialRef` names a store private to the task service;
there is **no supported general UI API yet** to create or update credentials.
The UI must not invent one or expose values from `providers.json`. The existing
legacy credential import is a narrowly scoped task service operation, not a
general editor.

Scene bindings reference group IDs, and model group members use the model IDs
returned by the actual Magpie catalog. A group can be absent or its models can
be unavailable; show that state instead of silently selecting a replacement.
The task service retains the existing Memory binding when migration encounters a
conflicting `taku-memory-jev` group.

## Known gaps

* The pinned upstream Magpie runtime still writes its private file directly.
  Its management UI/CLI edits can be overwritten by the Settings projection.
  Provider, model and group changes made there are therefore not a working
  Settings-backed write API. No updated runtime asset is published yet.
* Settings provides atomic replacement of one key, not an atomic transaction or
  compare-and-swap across these keys. The task service reads twice and checks again
  before projecting, but cannot detect a change after the final check, an ABA
  change, or a writer paused between multiple key updates. Concurrent read/modify/
  write on one key can also lose updates. There is no concurrency safety, so avoid
  concurrent editing until a shared writer protocol is implemented.
* No stable cross-process revision or credential mutation interface is defined
  yet. Treat this document as the current read contract, and coordinate before
  implementing writes that require multi-key atomicity.

## Concurrency constraints on the proposed single-key protocol

Moving the four values into one JSON value makes each replacement atomic, but
`settings get` followed by `settings put` is not compare-and-swap.
Android's `settings` command
offers no expected-version argument, so a retry loop around these calls cannot
detect every conflict. A real CAS needs a shared serialization authority used
by every writer, or an atomic conditional-write operation in the provider.

The unmodified Magpie process also writes `providers.json` independently. A
file observer reports writes after they occur; checking a hash and then renaming
a projection cannot prevent a Magpie write between the check and rename from
being overwritten. Hash, projected version and current Settings version alone
also do not establish the order of two independently completed writes during a
task service outage. A concurrency and recovery contract therefore needs both a
defined ordering/conflict policy and coordination at the file-write boundary,
or must explicitly preserve and surface ambiguous conflicts instead of claiming
lossless last-writer-wins behavior.

GUI and CLI checks must be repeated against the modified runtime and final
interface.
