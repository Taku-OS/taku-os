# JeV route enablement regression — 2026-11-12

The device catalog advertised `typesafe/jev-latest` as a decision model without
input modality metadata. Selecting it correctly saved `group/route-jev`, but
`MagpieDecisions.models()` dropped that ready group because `inputKnown` was
false. The settings UI could not find its saved selection and rejected enabling
JeV with “Configuration not saved” (translated UI text).

Ready decision groups now remain available when every member exists in the actual
decision catalog. Text support follows those members; unknown image support remains
false and `inputKnown` remains false. Missing members and non-decision groups are
still excluded. This preserves scene routing and does not invent image capability.

Also limit chat-loading failures to the chat page: a stale selected conversation
must not replace the JeV settings header with “Conversation temporarily unavailable” (translated UI text).

Validation:

- `bash system/assistant/test-jev-magpie-host.sh`: all suites pass, including
  89 loopback JeV checks. New coverage exercises text calls through the formerly
  omitted group and rejects image calls without making an upstream request.
- Assistant and Broker release builds pass.
- On isolated `emulator-5586` (`Taku_Launcher_Verify`), the GUI selects and enables
  the real `typesafe/jev-latest` route. The real “Test decision connection” action returns “Connection successful” (translated UI text).
- Reinstall/reopen preserves the enabled switch and route; header shows
  “Jev decision model” (translated UI text). `settings get global taku_system_ai_config` contains the same
  route and member as the GUI. Memory observation remains connected.
- The local ignored startup bundle contains the repaired APK and Broker, verifies
  their checksums and restores the Broker when the system image has the old version.

Evidence is local only: `out/local-setup/jev-route-verification/`.
No credentials or model provider configuration are committed.
