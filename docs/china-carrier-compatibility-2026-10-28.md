# China carrier compatibility and China Telecom VoLTE defaults

2026-10-28. Target: Pixel 9 / tokay / GUR25, Android 17 / API 37.
This round first fixes persistence of China Telecom VoLTE across restarts; other carriers and advanced network capabilities require separate acceptance.

## Observed issue

The current physical-device TakuOS 1.2.0 / `20261027185641` uses a China Telecom SIM with home PLMN `46011`.
Platform carrier configuration comes from `com.google.android.carrier`. Its bundle is nonempty but does not explicitly provide
`carrier_volte_available_bool`, so the final value falls back to the framework's disabled default.
After the assistant temporarily set this key true with `persistent=false`, both voice and data states became `IN_SERVICE`.
This does not establish acceptance of incoming/outgoing calls, verification messages, or defaults after restart.

## Scope for the four carriers

| Carrier | Android 17 reference internet APN | Independent checks |
| --- | --- | --- |
| China Mobile | `cmnet` | VoLTE/IMS, SMS, SA/NSA, LTE fallback; verify subject to provisioning for the number |
| China Unicom | `3gnet` | The APN name does not limit it to 3G; verify VoLTE, SMS, and SA separately |
| China Telecom | `ctnet` | This round first persists VoLTE availability for an identified voice SIM, without bypassing provisioning |
| China Broadnet | `46015 → cbnet`; reference database also includes `46060 → cmnet` | Separate SIM/IMS/profile checks; sharing a radio network does not make China Mobile configuration universal |

These are [AOSP Android 17 reference APNs](https://android.googlesource.com/device/sample/+/035858697891c2e1079255ef33656663618037a1/etc/apns-full-conf.xml), not evidence that the current ROM adopts every entry or that real SIMs from all four carriers passed acceptance.
Preserve separate APN roles for internet, `ims`, and MMS. Identify MVNOs by actual Carrier ID, specific ID, and SIM conditions, without guessing from phone-number prefixes or the serving network.

## Implementation in this round

`image/carrier-defaults/manifest.json` pins Telephony upstream commit
`2d0e6fb0059b8c1cab748f6f04453c9955a14fe3`, the patch, and before/after source hashes.
The patch enters `CarrierConfigLoader.getConfigForSubIdWithFeature()`; the subset API automatically reuses this entry.
After normal configuration merge and before persistent/temporary overrides, it supplies one return value only when all of the following hold:

- The device is `tokay`, and the queried valid subId matches the same Phone object's current subId; recheck before writing.
- SIM state is LOADED, with home PLMN `46003` or `46011`.
- Both carrier ID and specific carrier ID are `2237`; unknown or identified child MVNOs do not match.
- Normal configuration loading has finished, the platform provider bundle is nonempty, and merged service capabilities contain VOICE.
- A genuinely carrier-privileged app has not explicitly supplied this key.

Only `carrier_volte_available_bool` in the returned bundle is set true.
The patch does not change Google's original bundle, caches, user enhanced-4G preferences, number provisioning checks, IMS implementation, APNs, roaming, SA, VoNR, VoWiFi, or emergency-call conditions.
Explicit carrier configuration and both test overrides retain priority. Identity fields must not be used to claim absolute identification of every engineering test SIM.

The actual configuration hierarchy follows [official Android documentation](https://source.android.com/docs/core/connect/carrier);
VoLTE availability does not equal IMS registration or working service; see [IMS interfaces](https://source.android.com/docs/core/connect/ims).

## Build and evidence

The repair candidate uses product version **1.3.1**, Updater code **9**; the product name remains **TakuOS**.
It derives from the complete `8b77b5c8829ae9d9…` snapshot of archived 1.3.0 / `20261028112718`,
retaining IME 10062, Assistant 74, Memory 8, and Google app inputs without recollecting workspace components.
The parent layer carries `pendingCarrierRepair`, and all build/preview entry points refuse to use it directly.
The original runtime payload retains its bytes; only after rebinding the parent layer and passing existing source-repair checks may `telephony-preview` run.

Preflight independently compiles TeleService and Updater and checks the pinned SYSTEM TeleService APK, API 37, platform signature, and retained policy code.
Source, native aapt/apksigner logs, and original APK hashes bind to run-input; complete target-files, receipt, export, and
`load_baseline()` continue verifying the same evidence. Older archives without the carrier layer retain their original validation path.
All artifacts remain internal-test-only; this round changes no release channels and does not resume automation.

## Acceptance status and remaining steps

- Passed: real JVM policy checks, pinned-source patching and drift rejection, negative evidence tests, 1.3.1/v9 source migration, compatibility with existing predecessors, and independent source review.
- Pending: native Linux TeleService/Updater module preflight, complete image, OTA/payload/AVB signature verification, and immutable export.
- Pending: data-preserving Pixel 9 upgrade, reading defaults after reboot with both overrides empty, and IMS/incoming/outgoing call/SMS acceptance.
- Pending: real China Mobile, China Unicom, and China Broadnet SIMs; SA, VoNR, VoWiFi; SIM reload/dual SIM and long-term standby.

Everyday use also requires separate acceptance of domestic connectivity checks/DNS, automatic time/time zone, GNSS/assistance and indoor positioning, lock-screen notifications, and background power use.
Installed Google services, network reachability, account login, and payment certification are separate conditions. Do not label host tests, menu toggles, or `IN_SERVICE` as business-service acceptance.
