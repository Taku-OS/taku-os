# Unified System AI and Magpie synchronization

`settings get global taku_system_ai_config` reads the public JSON object. The fields are `version` (1), `updated_at` (Unix epoch milliseconds), `updated_by`, `providers`, `models`, `groups`, and `scenes`. Writers replace the complete JSON object, set `updated_at` to their current time in milliseconds and identify themselves in `updated_by`; credentials belong in Magpie, not in this public setting. The migration preserves existing groups and scene bindings and keeps credentials in the private Magpie credential file. The system AI enable switch and per-app permission switches remain separate.

The Broker observes the public setting and atomically projects provider, model and group changes to Magpie. It saves the projected file's SHA-256 beside the file to suppress its own file notifications, including after restart. Magpie changes are imported with credentials stripped into the private credential file; scenes remain in the public setting. On startup, an unrecognized file newer than `updated_at` wins; otherwise the setting wins. Equal timestamps favor the setting. Clock accuracy and filesystem timestamp granularity affect offline last-writer-wins decisions. Writers must supply accurate timestamps.

The Broker logs best-effort summaries of changes it actually observes, including before/after provider, model, group and scene counts, SHA-256 values and the inferred direction. Logs contain no raw configuration or credentials. File observers and settings notifications can coalesce, the Broker may be offline, and simultaneous writes can overwrite one another; this is not a complete audit trail and does not capture every intermediate overwrite. Settings has no compare-and-swap API, so the Broker checks for intervening changes before writing but cannot eliminate the final race window.

## Mac mini AVD verification — 2026-10-31

On API 36 `Taku_SystemAiGroups_20261030` (`emulator-5690`), the development APK was installed and the newly built Broker and pinned Magpie 0.1.2204 were run from `/data/local/tmp`; the system partition was not modified. Because the AVD's WebView Browser Tester did not render the page correctly with its graphics configuration, a disposable software-rendered WebView container displayed the actual local Magpie management page. The fixture provider and its credential were synthetic.

- In the Magpie page, changed the provider name to `GUI-Provid` and deselected `jev-text`. `settings get global taku_system_ai_config` returned `updated_by=magpie`, the new name, and models `fixture-chat` and `jev-vision`, without the fixture credential. [Page after save](unified-gui-change.png).
- Wrote `CLI-Provider` and models `fixture-chat` and `cli-model` to the single JSON key with `updated_by=cli`. The private projection contained those values, and the reloaded Magpie management page displayed both the new provider name and selected `cli-model`. [Provider and model detail](unified-cli-change.png).

This verifies the local synthetic configuration path on the disposable AVD, not a real provider, physical device, full ROM, or persistence of the temporary processes after a reboot.
