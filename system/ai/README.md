# Taku system AI

The shared entry point is **Taku AI** in Android system settings. Assistant settings, the input method, and Memory open the same native page and reuse existing Magpie, phone Codex sign-in, and Broker without maintaining separate accounts or model keys.

## Configuration and models

Android `Settings.Global` is authoritative for system AI toggles, app permissions, Magpie model groups, and scene bindings. The Taku AI page and on-device `settings` CLI read and write the same settings; the system generates private Magpie file projections. Scene bindings reference `group/<id>`, with Memory defaulting to the separate `group/taku-memory-jev`. Upgrades copy the original Memory route. If an existing group with that name differs from the old binding, preserve the old binding until the user chooses in settings. Magpie manages models and credentials; credentials are never written to Global Settings.

See the [System AI data contract](../../docs/system-ai-data-contract.md) for the current interface and unfinished boundaries: the existing Magpie management page can still bypass Settings, and writes across keys are not transactional. Bidirectional provider, model, and group synchronization is incomplete until the runtime and concurrency protocols are finished.

- An empty input-method model selection means “follow the input-method scene binding”; explicit model selections remain overrides. The input method still manages screenshot-source allowlists, password/protected-page exclusions, and revocation on typing.
- New Memory installations default to “follow the memory scene binding.” Existing explicit selections remain overrides and can be switched back. Collection, model analysis, Agent queries, and proactive suggestions retain independent toggles; shared model configuration does not enable them automatically.
- Memory keeps its SQLite database, durable queue, and bounded batches. Ordinary text models return strictly validated classification JSON; explicitly selected Jev models retain the original Decisions protocol. Ordinary models are not presented as Jev.
- System-setting changes invalidate old requests. Memory retains pending records, refreshes its model, and retries; old-model results cannot overwrite new state.

At most 32 recent call records retain only source, model, time, duration, status, and fixed error types, without request text, images, or replies. Tap a record for its failure category. Subscription limits reuse the native account usage page. Credentials stay in existing private Magpie / Codex storage.

## Reuse from other apps

Include [AiClient.java](client/org/agentos/ai/AiClient.java) as a source dependency in an Android project and declare:

```xml
<uses-permission android:name="org.agentos.assistant.permission.USE_AI" />
<queries><provider android:authorities="org.agentos.ai" /></queries>
```

This normal permission grants interface access, **not automatic authorization for model calls**. On its first settings read, a new app is registered under the package associated with its actual Binder UID, disabled by default. The app can open `AiClient.openSettings()` so the user can allow it individually in Taku AI.

```java
// Run in the background; the client rejects blocking calls on the main thread.
AiClient ai = new AiClient(context);
JSONObject state = ai.settings();
if (state.getBoolean("enabled")) {
    JSONObject reply = ai.text("Text to organize for this request");
    String text = reply.getString("text");
}
```

`image(prompt, dataUrl)` accepts explicitly supplied JPEG / PNG base64 images, not remote image URLs, and grants no system screenshot access. Ordinary apps always use the system default model and cannot forge a source or submit arbitrary model overrides. The interface exposes no account credentials, other apps' settings/usage, or memory reads.

Public generation supports at most two concurrent requests and 12 requests per app per minute. Text is limited to 8000 characters, image data URLs to 300000 characters (leaving Binder transaction headroom), and output to 1000 tokens. It grants no tools or system-operation permissions. Input-method screenshot entry retains separate signature, package, and default-IME checks; Memory records retain their existing signature permission.

## Gateway isolation

Upstream desktop Magpie trusts loopback by default. Android apps share that address, so the launcher creates a separate `taku_ai` OUTPUT chain allowing only UIDs 0 / 1000 to connect to loopback TCP 3425 and rejecting other UIDs. Other ports are outside this chain; management on 3430 retains its existing credentials.

Ordinary apps request models through ContentProvider → system Broker → Magpie. The gateway's compatibility Bearer placeholder is not a security credential. Actual caller identity, system policy, and UID isolation jointly enforce app authorization.

## Validation and development installation

```sh
bash system/assistant/test-system-ai-host.sh
bash system/assistant/build.sh
bash system/memory/build.sh
bash system/memory/build-tests.sh
bash system/inputmethod/build.sh

# Owner-authorized development device; installs artifacts without flashing or rebooting.
python3 system/assistant/tests/system-ai/deploy.py DEVICE_SERIAL \
  --allow-physical --output out/system-ai-device/deployment
```

Physical UI checks use `device.py`; real observation and model organization use `memory-live.py --allow-live-model-calls`. Record isolated SQLite tests, ordinary-app public-client tests, root gateway success, and ordinary-app direct-access rejection separately so a model-service outage is not mistaken for successful permission enforcement.

Development installation uses APK updates and temporary system-file bind mounts while keeping SELinux enforcing. Shared configuration stays on the data partition. **Development runtime mounts disappear after a full device reboot** and must be restored or delivered in a formal ROM update. Existing assistant and Magpie product modules still compile and package the source; no new framework service or duplicate account system is needed.

The shared system AI flow passed actual-model acceptance on Pixel 9 / Android 17. See the [2026-10-23 acceptance record](../../docs/reviews/system-ai-2026-10-23/README.md) for versions, installed hashes, screenshots, and reboot boundaries.

## Acceptance before and after a ROM upgrade

After obtaining the device handoff window and before installing a new ROM, capture the current baseline:

```sh
python3 system/ai/capture-baseline.py DEVICE_SERIAL --allow-physical \
  --output out/cold-acceptance/RELEASE_ID/before.json
```

The script reads current models, app permissions, input-method settings, Memory toggles, and active APK metadata. It checks only account-file permissions, not contents, and makes no model calls, installations, or settings changes. It refuses to save if configuration, boot ID, or active APKs change during capture, and never overwrites a baseline. Historical acceptance baselines cannot substitute for this upgrade's state.

After formal ROM installation and a full reboot, run `cold-check.py` with this `before.json`, the actual new build number, and the image's `system-ai-rom.json`. Only after that passes, run `tests/cold-ui.py` and the input method's real-model UI regression. Supplementary APK installs, temporary runtime mounts, or manually started services cannot establish successful cold-start acceptance.
