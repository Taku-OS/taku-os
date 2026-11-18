# Native confirmation and foreground draft candidate

This candidate adds reusable confirmation and draft tools to the existing phone Agent. It does not add a restaurant-specific execution script.

## What changes

`confirm_action` accepts a title, summary, one to eight labeled fields, and a confirm label. `field`, `fact`, and `notice` blocks render in the native bottom choice surface. Confirm, modify, and cancel return distinct decisions. The pending request owns an immutable copy of the displayed details; its response includes the actual snapshot, content hash, request ID, thread and turn. Rendering, dismissing, expiring, and old revisions never grant approval. Consumed or stopped requests cannot be answered again.

The Agent instructions require a new confirmation if the actual reservation details change, distinguish free reservations from payments, and distinguish confirmed bookings from requests awaiting merchant acceptance. This is an Agent instruction plus a structured human response: generic tap operations do not independently understand reservation semantics or cryptographically consume a booking permit.

`prepare_foreground_draft` is the one scoped foreground exception. It verifies display 0, the active application, exact visible conversation header, a single writable composer, and an empty or identical draft. It uses only `ACTION_SET_TEXT`, reads the result back, and never sends, presses Enter, navigates, or invokes an IME action. Different existing drafts are preserved. The helper is isolated, bounded, and interrupted when its owning turn stops. Android does not expose an atomic conversation compare-and-set; repeated target validation and fail-closed behavior reduce that race without claiming an atomic guarantee.

Screen understanding still uses the original image. The draft helper reads only candidate header/editor accessibility fields to validate the write target; it does not turn the screenshot or conversation into a transcript for the model.

## Evidence boundaries

- Host tests cover confirmation validation, rendering mapping, request routing and cancellation, draft target policy, and surface layout/wire compatibility.
- Pixel 9 runs Assistant 71, Surfaces 4 / 0.3.0, and the candidate broker over the existing Taku OS 1.0 image. These are component updates, not a newly accepted complete ROM.
- A clearly labeled synthetic native card has been rendered and visually checked on Pixel 9. It is not proof that a restaurant was booked.
- Four isolated Android draft-fixture cases passed: empty draft write/readback, identical-draft idempotence, preservation of a different draft, and rejection of a wrong conversation. Send, Enter, and IME-action counts were zero. No fixture is connected to an account.
- Long field values now stack below their labels; long fact text uses normal body size. The actions stay in a separate footer while the body scrolls. The revised native APK has been visually checked on Pixel 9 in English at system font scales 1.0 and 1.3, including scrolling to the final conditions with all three actions visible. These are explicitly synthetic layout fixtures. Narrow-width and keyboard geometry retain host coverage; they are not additional physical-device acceptance claims.
- Run 05 stopped while waiting for the dining name and did not submit a reservation. Run 06 used the real WeChat image plus explicit previously supplied human parameters. It reached the real Amap route, the real Meituan form, the native confirmation, and a successful reservation after the human explicitly confirmed that exact free booking.

## Real Pixel 9 result

On 26 October 2026, Amap showed a walking reference from Longyang Road metro station to the Longyang Plaza Haidilao location: 55 metres, approximately one minute. This was a chosen reference origin, not the device's GPS location. Meituan then accepted one free reservation for that restaurant at 20:00 on the same date, with three guests in the main dining area. The actual app displayed **Reservation successful** (translated from Chinese). The seafood-allergy note was present before submission; restaurant accommodation of that allergy was not separately confirmed. No paid add-on was selected.

The last step did **not** pass: the foreground draft helper could not validate the WeChat title and composer. A narrow, read-only diagnostic found an empty accessibility root for WeChat while the system bar and keyboard roots were available. Changing the proposed title spacing did not solve it. No draft was written and no group message was sent. The helper keeps its strict target checks; an empty root is now classified as `FOREGROUND_CONTENT_UNAVAILABLE` rather than misleadingly reporting a title mismatch. That final classification change has host and build validation, not a new successful WeChat acceptance.

The run also exposed a reply-ordering race: the next phone operation could arrive before an answered question was removed from the pending map. The candidate now validates and consumes the pending request before publishing the response. A synchronous next-operation regression fails on the old code and passes on the fix. Invalid replies remain answerable; an uncertain transport write never restores a replayable approval. This fix has 61 routing checks and a complete build; the recorded business run used the preceding broker.

This is a real successful reservation with an unfinished chat-draft handoff, not a stable one-pass showcase or complete-ROM acceptance. There was no new simulator acceptance in this run. The complete ROM remains the existing 1.0 image with candidate component updates.

Local evidence is retained under `out/manual-screen-case-20261026/confirmation-integration/`, `run-05/`, and `run-06/`. Run 06 includes a 600.007-second background sampling video with original timing and four separate foreground screen recordings. They include waits and failed draft attempts; they are not presented as a gap-free polished recording. Account-containing images, recordings, and contact details are not published to the public documentation site.
