# Magpie Settings integration — 2026-10-30

This page records the earlier four-key implementation and its historical device tests. The current single-key contract, bidirectional synchronization, limitations and current AVD evidence are documented in [Unified System AI and Magpie synchronization](unified-sync.md). The old key names and commands in the historical tests below no longer describe the current interface.

## Device verification

Disposable API 36 AVD `Taku_JevMagpie_20261021` (`emulator-5680`), with temporary development runtime and APK installation. No physical device was changed. A local synthetic HTTP upstream supplied model metadata and returned `shell route works`; this is not a live model test.

While the Taku AI page was open, shell writes set the provider (with a previously migrated fixture credential reference), model, group, and binding:

```sh
settings put global taku_magpie_providers '[{"id":"fixture","name":"Shell Fixture","preset":"remote-magpie","chat":"http://127.0.0.1:64643/v1","modelsURL":"http://127.0.0.1:64643/v1/models","credentialRef":"fixture"}]'
settings put global taku_magpie_models '{"fixture":["shell-model"]}'
settings put global taku_magpie_groups '[{"id":"shell-verified","name":"Shell Verified","members":["fixture/shell-model"],"routing":"order"}]'
settings put global taku_magpie_scene_bindings '{"memory.text":"group/shell-verified"}'
magpie group shell-verified
magpie models
```

`magpie group` showed `group/shell-verified` with `fixture/shell-model`. A shell-invoked existing Broker socket probe generated through `memory.text`: the Broker returned model `group/shell-verified`, the synthetic upstream received `shell-model`, and the response was `shell route works`. The open settings page displayed the same provider, model, group and binding, captured in [magpie-cli-settings.png](magpie-cli-settings.png). An external direct `magpie group add` was reverted to the Settings group by the file observer.

A restart with the migration marker reset and the models key deleted repopulated models from the private file while preserving existing group and binding keys, then set the marker to `1`. A synthetic Jev credential import wrote a private credential reference without exposing the key in Global settings. Assistant build, System AI host tests and Jev/Magpie host tests passed. The fixture's HTTP server and ADB reverse are test infrastructure and do not represent a real account or production model.


## Dynamic scene page and legacy slot migration

The Broker converts the former `taku_ai_group_text` and `taku_ai_group_image` settings, including the earlier implicit defaults, into groups and explicit scene bindings on the first configuration read. Existing scene bindings take precedence. Old generic role bindings are translated to the corresponding explicit scenes, and the two legacy keys are deleted after successful readback. The `taku_magpie_slots_migrated_v1` marker makes the conversion retryable. Legacy main-connection and Jev selections migrate on access to the `assistant` and `jev` scenes. Settings writes remain non-transactional and concurrent writers can race.

On the disposable API 36 AVD, a synthetic upstream returned responses through the migrated Memory and IME groups and a Decisions response through the migrated Jev group. Main-connection readback showed its migrated group and the private connection file no longer held a model. Existing `memory.text` won over the old text slot. While the page was open, `settings put` added a new group and `work.summary` binding; both appeared, and the scene dialog successfully changed the binding. The empty state was also checked. These checks do not cover a real model, rebuilt IME APK, full ROM or physical device.

- [After migration](settings-dynamic-migrated.png)
- [Group and scene added from Settings](settings-dynamic-added.png)
- [Scene rebind dialog](settings-dynamic-rebind.png)
- [Assistant and Jev entries](settings-dynamic-assistant.png)
