# TikTok character preview (v1)

This feature adds an ordinary-UID native Try-On application and a narrow entry in
the existing Kotlin Home Assistant. The native app owns reference decoding,
camera/model resources and the result window. Home owns the original system
invocation, voice request and final Try on confirmation. There is no automatic
LIVE observer, replacement assistant runtime or camera injection.

## Interfaces and ownership

| Interface | Authoritative implementation |
| --- | --- |
| `content://org.taku.tryon.api` | [Native controls, credentials and state](../system/tryon/README.md#gui-and-agent-api) |
| `taku.cosplay.voice.v1` on the same provider | [Home-only reference/ticket handoff](../system/tryon/README.md#tiktok-character-handoff), declared in [the shared contract](../system/tryon/contract/org/taku/cosplay/api/CosplayContract.java) |
| `content://org.agentos.agent.home-assistant` | Existing Home session and its character confirmation handlers |
| Audio Engine `assistant-entry` | Existing shared dictation policy, including `en-US`; [settings and commands](../system/audio-engine/README.md) |

Home retains the existing screenshot authorization and generic request path. Only a
recognized character request with an eligible TikTok frame enters this workflow.
The compact English card offers Not now and Try on. Confirmation is bound to the
current request; cancellation, replacement and expiry invalidate pending work.
The shared wire binds the bounded JPEG to original invocation and capture times,
source package and one-use ticket. Package names alone never authorize a caller;
the apps verify actual installed UIDs and signing identities.

The direct Activity remains transparent until the native camera window acknowledges
handoff. Preparation has no intermediate white page. Failure shows a closeable
English alert; a paused or destroyed invocation cannot reopen a late alert.
Only an explicit start/confirmation can create a cloud attempt, at most one session
per attempt and at most 60 seconds. A decoded returned frame, not a counter or
signaling acknowledgement, establishes visible model output.

## Setup and Agent controls

Install both source-built apps with the documented trusted development or platform
identities. Enable TikTok character requests and camera preview in the native app,
configure the desired model using its write-only credential path, and grant camera
and overlay permissions. Voice language is configured in Taku Audio; there is no
independent Try-On language preference. Exact native commands, setup switches,
error codes and signing requirements live in the module README above.

Home uses the same handlers for its GUI and these permission-checked Agent calls:

```sh
content call --uri content://org.agentos.agent.home-assistant --method assistant/get
content call --uri content://org.agentos.agent.home-assistant --method assistant/character-cancel --arg '{"requestId":"CURRENT_REQUEST_ID"}'
# Explicit camera/model consent, only for the actual current confirmation:
content call --uri content://org.agentos.agent.home-assistant --method assistant/character-confirm --arg '{"requestId":"CURRENT_REQUEST_ID"}'
```

`characterTryOn` is null or contains only `requestId`, `phase` and `message`; phases
are `checking`, `confirming`, `starting` and `failed`. Stale actions fail with
`STALE_REQUEST`. No image, ticket or credential is returned. Closing the input also
cancels its pending character request.

## Build and verification

Run `npm run check:tryon` and `npm run build:tryon` from the root. Run the Agent UI's
type, lint and Node tests, and native Home `compileReleaseKotlin` and
`testDebugUnitTest` Gradle tasks after normal Expo prebuild. The module's character
host scripts additionally compile actual policy/owner code with explicit Android
doubles; their output is not Android runtime acceptance.

APK source, signatures and hashes, GUI/Agent settings, real system capture, physical
navigation gestures, ASR, camera/cloud output and full ROM acceptance are separate
evidence. A stock SDK emulator has no Taku Launcher navigation-handle patch.
APK installation or an injected transcript therefore does not prove a physical
bottom-handle gesture or real recognition. No model keys, recordings, reference
images, APKs or generated build output belong in the source contribution.

## Optional TikTok LIVE handoff

`system/shopping-observer` owns LIVE verification, bounded screenshot selection, image recognition and explicit outfit selection. It provides one source-issued admission to `org.taku.tryon/.realtime.DirectLiveTryOnActivity` using the shared `contract/LiveTryOnContract.java`. The admission is bound to the target UID/signer and immutable source reference, expires after 30 seconds and can be redeemed once; the reference grant lasts at most 120 seconds. Native validates the actual source authority/package and admission response before reserving a camera attempt. The transparent entry closes only after that generation's window attaches, or after its bounded failure/cancellation path.

The native app remains the owner of camera, model transport and cleanup; the observer does not receive model credentials. Observer settings and Agent actions use `content://org.agentos.shopping.api` (`status`, `configure`, `tryOn`, `cancel`, `retry`, `select`) and the same guarded handlers as its UI. Native status/stop/window controls remain at `content://org.taku.tryon.api`. A LIVE visit never starts Lucy; confirmation or a separately authorized explicit API call is required. See the observer README for parameters, errors and validity checks.
