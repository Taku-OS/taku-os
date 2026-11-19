# Screenshot suggestions (2026-10-23)

## Data flow

- Manual: Android system long-press assistant invocation → **Screen suggestions** → original screenshot directly to the configured system image model → suggestion → user acceptance → new Agent thread and one TaskBroker task. Jev is skipped for this explicit request.
- Automatic decision entry: original screenshot → Jev gate → when actionable, the same captured image goes to the system image model → suggestion → acceptance → Agent. This code path is not a claim that continuous background triggering has passed on the Pixel.
- Neither path performs OCR, screen transcription, or a screenshot-to-text-to-model stage. No AssistStructure text is passed to either model.
- Models and credentials stay in phone-local Magpie/System AI. The assistant is a separately controllable consumer, `org.agentos.assistant`, in **Taku AI**. A new consumer is registered disabled; enable its AI permission before use.
- Suggestions remain in memory, expire after two minutes, and create no Agent task before acceptance. An accepted suggestion carries its capture to a fresh conversation, preserving unrelated drafts. Chat messages and reservation submission are not authorized by the suggestion.


## Verification

| Target | Result |
| --- | --- |
| Host | Assistant APK compiled; 13 model-output validation checks passed. |
| Pixel 9, existing Android 17 build | Assistant 65 component update installed. Real WeChat group screenshot → `codex/gpt-6-luna` through phone-local Magpie → content-specific dining hint passed (4.8 s and 7.8 s observed calls). No Agent task before acceptance; acceptance created exactly one Agent task in a fresh thread. Real Agent read the attached screenshot and asked for missing location/time details. |
| Emulator | This new screen-suggestion revision has not been tested here. Earlier Jev tests are separate evidence. |
| Full ROM / double power press | Not verified by this component update. Native double-power routing is still pending. |
| Current cross-app continuation | Amap route and Meituan review were verified in an earlier manual continuation; the new screenshot-hint-originated task has not yet completed that full route at this checkpoint. |

Android 17 strips screen-access flags from an assistant service's disallowed background self-trigger. Use the real system power-long-press entry for this revision. The DUMP-protected development receiver does not bypass that restriction; a missing screenshot fails without calling a model. Do not disable platform privacy restrictions to claim acceptance.

This round accepted screenshot capture, real image-model suggestions, tap-to-continue, and real Agent responses on the physical phone. It does not mark emulator, automatic background triggers, double-power invocation, or full-ROM testing as passed. Original group-chat screenshots, videos, device settings, and task evidence remain in ignored `out/` and are not distributed with source.

```sh
bash system/assistant/build.sh
bash system/assistant/test-screen-suggestion-host.sh
```

## Expo device regression

Run `bash system/assistant/build-screen-tests.sh` and install `out/screen-background-tests/fixture.apk` on a dedicated API 36 emulator with the current Expo and Kotlin Assistant components. Enable assistant screenshots and a live system image model in Taku AI. Push `out/screen-background-tests/test.jar` to `/data/local/tmp/taku-screen-tests.jar` and run the live probe with `-e liveAuthorized true`.

```sh
adb -s "$SERIAL" shell uiautomator runtest /system/framework/android.test.base.jar \
  /data/local/tmp/taku-screen-tests.jar -c org.agentos.tests.BackgroundScreenUiTest#testLiveBackgroundAskUser \
  -e liveAuthorized true -e output /data/local/tmp/taku-background-screen/
```

The probe opens the Expo suggestion on a synthetic page, accepts it, and closes the suggestion. It checks that the Agent asks its question over the source app and continues after the selected answer, while the chat composer stays closed.
