# Three background apps collaborate on a dinner plan

Three on-phone Agent branches ran successfully on Pixel 9: rednote searched store notes, Amap checked a route to the same mall, and Meituan inspected an existing reservation receipt. Each app operated on its own background screen while WeChat remained on the main display; native execution records confirm overlap among the three branches.

**As of October 26, 2026, candidate `ec891cd` passed three-way real-query acceptance through temporary deployment on Pixel 9 / Taku OS 1.0.1.** The computer controller dispatched three `phone-native` branches, with Codex on the phone executing each branch's tools. Automatic decomposition of one task and automatic aggregation of all three results are not yet implemented. No new reservation or WeChat message was sent. These components are not yet integrated into a complete ROM.

Evidence for this round consists of `run-01` native execution records for all three branches, final AgentViews, and screen assets. Earlier video serially switching Amap and Meituan on one background display, and screenshots showing only three open apps, cannot substitute for this concurrent business-operation evidence.

## Current verified scope

| Environment | Verified | Not yet verified |
| --- | --- | --- |
| Host tests and builds | `ec891cd` regression for multiple screen slots, thread binding, package occupancy, generation checks, cancellation isolation, and failure recovery passed; two subsequent failure-boundary fixes also passed local regression and JAR builds | Later fixes are not deployed/retested on the phone and cannot count toward this recorded version |
| Emulator | Earlier single-background-screen acceptance retained | No new multi-screen emulator acceptance in this round |
| Pixel 9 / Taku OS 1.0.1 | Temporary `ec891cd` deployment; three `phone-native` branches bound to different background screens with actual overlapping execution; rednote found same-store notes, Amap found the same-mall route and L4 destination, Meituan found the original successful-reservation receipt; WeChat stayed on the main display | Automatic dispatch/aggregation, unified confirmation card; complete physical-device failure regression such as cancellation/restart; reacceptance of later fixes |
| Complete ROM | Candidate components not yet integrated | Boot, lifecycle, and full Case acceptance after components are fixed in the image |

At Pixel 9 capture time, rednote, Amap, and Meituan had display IDs **6, 7, 8**. These are evidence for this run only, not fixed configuration for later calls; actual operations still use returned `screenKey`, `generation`, and `displayId` bindings.

Initial-window evidence is in ignored `out/multi-screen-case-20261026/first-three-screens/`, including four source screenshots and metadata for three background displays. Its `four-screen-overview.png` shows only startup state.

Business evidence completed after Wi-Fi recovery is in `out/multi-screen-case-20261026/run-01/`: `rollout-*.jsonl` records native execution of the three branches, and `agent-view-*.json` preserves final results; `main.mp4`, `background/`, and time-audit files align the four screens. Active intervals from status polling may end late; native rollouts determine actual execution overlap. Real chat, account views, and raw records are not distributed with documentation.

## What the three branches actually found

The conditions came from the real dinner-planning chat: near Longyang Plaza in Pudong, Haidilao, 20:00, 3 people, one with a seafood allergy. Check the date at runtime; October 26 in the historical demonstration must not become a fixed future-task date.

| View | Task in this round | Actual result and limitation |
| --- | --- | --- |
| WeChat main display | Retain the real dinner-planning chat as task context | No message sent; automatic aggregated confirmation card not yet verified |
| rednote background screen | Search notes and cautions for Haidilao Longyang Plaza | Store location page pointed to floor 4, Longyang Plaza, 2000 Longyang Road; read one note linked to that location and another mentioning Longyang Plaza. Dates lacked a year, so they cannot be called recent reviews; no same-evening queue information or commitment to accommodate seafood allergy was found |
| Amap background screen | Use chat location “Longyang Plaza” to check the same-store route | Store address matched; walking-route destination was L4, with “Go straight 1 meter to arrive.” **One meter is a map-point calculation, not actual walking distance inside the mall**, nor a route starting at live device GPS; no directions for getting upstairs were found |
| Meituan background screen | Read the same store's existing reservation without placing another | “All orders” and reservation details showed the original October 26, 2026, 20:00 reservation for 3 in the main dining area, status “Reservation successful, awaiting arrival,” and note “1 person has a seafood allergy.” No reservation was added, changed, or canceled; the note is not merchant confirmation that the allergy can be accommodated |

All three started from the same store conditions. rednote reviews were not a prerequisite for Amap to begin, nor did Meituan need to wait for review retrieval before reading the order. They shared task conditions and executed on their own screens; the computer controller started all three in this round.

Aggregation must verify that all three refer to the same store, comparing at least name and address. If results differ, only the relevant branches should recheck; do not combine store A's reviews, store B's route, and store C's reservation. Neither reviews nor an entered allergy note constitute a merchant commitment to accommodate seafood allergy.

## Exceptions and version boundaries

This execution was not failure-free throughout:

- Meituan's first turn stopped at a security-verification page without finding the order. Under prior authorization, the controller continued the same branch with a second turn; the Agent passed verification with one normal swipe, then read the existing receipt. Recording and documentation should retain that stop/continue sequence.
- One rednote `type_text` readback returned `TEXT_READBACK_MISMATCH`. Subsequent actual page operations completed the search and read the store/notes; the earlier error must not be removed to describe every tool as successful.
- Amap's store-detail area displayed a network error at one point; the branch ultimately obtained the stated results from the store page and walking-route details, without verifying indoor passages or obtaining allergy-accommodation commitments.

`run-01` records temporarily deployed **`ec891cd`**. Two later boundary fixes passed local regression and JAR builds: archived/deleted threads can clean screen leases under original ownership, and continuous gestures stop further input if the host generation changes or the connection drops. These fixes have not been deployed/retested on Pixel 9, so this recording cannot establish physical-device acceptance of the later version.

## Execution boundaries

- Reuse the same on-phone Codex App Server, Magpie, and credential management, with each native thread bound to its own execution screen.
- Do not simply make the old `TaskBroker.run` queue concurrent; it still has single-task execution state. First use the existing native multi-thread protocol for the three branches.
- The server establishes bindings; models cannot arbitrarily choose `displayId=0` or another task's screen. Stop operating when window generation changes; an Android-reused display ID is not the original screen.
- Each screen has its own input queue, gestures, and screenshots. The system accessibility connection used for text input temporarily uses a short critical section to avoid simultaneous connection conflicts; this does not mean all three apps stop running.
- The same app package cannot be occupied by two branches at once. Android single-instance Activities may migrate between displays; three screens are not three independent Android systems.
- Image understanding continues to use original screenshots, without OCR, screenshot-to-text, or page-text replacements as intermediate stages.
- Product orchestration should aggregate results into one confirmation card after all three checks finish; this is not connected yet. Canceling one branch should cancel only its input, then explicitly release its screen after the branch ends. Other branches continue; canceling the whole task terminates all related branches individually. These failure paths still require dedicated device regression.

The candidate implements display management, thread binding, input isolation, and multi-screen recording, with real queries completed by three on-phone Agent branches. Automatic three-way decomposition (fan-out), result association/aggregation (join), and unified confirmation-card presentation still require task orchestration. Computer dispatch in this round cannot be described as automatic completion of these steps by the phone product.

This round only read the previously completed real reservation, without placing another order or sending WeChat group messages. The earlier reservation authorization applied only to that earlier reservation.

## Binding an execution branch

The controller first creates or loads an on-phone Codex thread through the existing native protocol, then calls `executionScreen` before starting the turn. This is a controlled service interface, not a model tool for arbitrary screen selection.

```json
{
  "op": "executionScreen",
  "action": "bind",
  "threadId": "existing on-phone thread ID",
  "label": "Route to the store",
  "packageName": "com.autonavi.minimap"
}
```

`bind` returns server-generated `screenKey`, `displayId`, and `generation`. Record all three before starting the task. rednote, Amap, and Meituan each occupy one branch; a package cannot occupy two. When the model calls screenshot, tap, swipe, and text tools, the server resolves the screen from the thread instead of accepting a display ID from model parameters.

| Operation | Purpose and constraints |
| --- | --- |
| `list` | Inspect existing bindings, at most three |
| `bind` | Allocate a screen to a loaded, idle thread; supply `threadId`, `packageName`, and optional `label` |
| `release` | Normally close the screen and unbind after idle; exact original `threadId`, `screenKey`, and `generation` required |
| `releaseStale` | Clear a stale binding when the old host is lost or generation changed, using the same ownership fields without stopping an unidentified new host |

Threads running, retaining phone-tool workers, or awaiting user answers cannot be rebound/released. Old bindings need readmission after runtime restart; stale/released explicit bindings do not silently fall back to a default screen. After stale-binding cleanup, explicitly `bind` again to obtain a new screen.

## Proving real concurrency

A four-panel video alone is insufficient. Acceptance must also retain:

1. Three different nonzero display IDs on the same device, their `screenKey`/`generation`, and corresponding app windows.
2. Start/end times and actual tool records for three native on-phone threads, proving execution overlap. Label model waiting and static apps honestly.
3. Per-screen tap/scroll/text readbacks proving input does not cross screens; main-display WeChat focus/messages remain unaffected.
4. Result provenance and same-store matching for all branches. Mark complete only after results exist.
5. Failure/recovery records for screen restarts, task cancellation, and text-input conflicts, without silently switching to the default display.

Clearly labeled test apps can verify isolation, cancellation, and lifecycle on an emulator; this round has not added that testing. Pixel 9 `run-01` additionally verifies actual three-branch execution overlap, each screen's tool calls, and the stated business results. Overlap includes model inference, tool waits, and app waits, not simultaneous touches at every instant. Cancellation, screen restart, and later failure fixes need separate acceptance; full-ROM acceptance follows after components are fixed in the image.

Two platform-compatibility issues were addressed before deploying `ec891cd`: Android mksh marks persistent file descriptors close-on-exec, requiring explicit `9>&9` when invoking lifecycle file locks; client `LocalSocket` must `connect` before `setSoTimeout`. The former passed a Pixel 9 file-lock probe, and the latter was verified through actual status queries/screenshot export. Separate `run-01` records/screens support the business results.

## Four-screen recording and editing

The main display uses real Android `screenrecord`; each background display samples its own mirror socket. The background recorder [record-multi-screens.py](../scripts/record-multi-screens.py) sends only `status` and `frame`, without operating the phone.

Before recording, a controlled ADB connection creates separate loopback forwards for the same device's three named sockets and writes actual bindings into a private output directory:

Top-level configuration fields are `deviceSerial` (actual device serial) and `screens` (at most three binding objects). Each binding contains:

| Field | Source |
| --- | --- |
| `screenKey` | Key returned by server binding |
| `port` | Local loopback forwarding port for this socket, integer |
| `displayId` | Nonzero display ID returned by binding, integer |
| `generation` | Host generation returned by binding |

Do not hardcode display IDs or combine recordings from different devices/times as “simultaneous.”

```sh
python3 scripts/record-multi-screens.py \
  --bindings out/multi-screen-case/bindings.json \
  --output out/multi-screen-case/recording \
  --duration 600 --fps 5
```

The recorder starts a separate reader thread per display, using a shared host start time and recording device-frame metadata before/after each read. One slow connection does not block the others. Distinguish frame time from receipt time; overlapping sampling windows alone do not prove Agent execution overlap.

Editing storyboard:

1. **Four-screen overview**: WeChat and three background apps visible together, labeled with their activities.
2. **Focus on the current highlight**: enlarge the screen with a key result/interaction while the other three keep playing as thumbnails. Thumbnails do not imply paused tasks.
3. **Move to the next highlight**: change focus based on actual tool events/results, without titles claiming completion early.
4. **Return to overview and aggregate**: show how the three results converge on one confirmation card.

All screens share one editing timeline. Speedups, removed recording gaps, or pauses must apply to all views together. Until real assets are complete, interaction storyboards must be labeled illustrative, not delivered as actual concurrent phone footage.

Real chats, accounts, and screenshots stay in ignored output or controlled private storage, outside distributed code/documentation.
