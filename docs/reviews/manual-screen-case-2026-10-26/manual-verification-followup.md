# Pixel 9 manual verification follow-up — October 26, 2026

The complete cross-app case has **not passed**. An operator recovered Meituan access on the main display, but this does not establish autonomous verification or a repeatable complete run.

The background diagnostic used direct SurfaceFlinger screenshots. The puzzle appeared aligned in `20-direct-held.png`; after release at the final MOVE coordinates, `21-direct-after-release.png` showed a request exception and refusal. The cause remains unknown. No further successful background verification is claimed.

On the main display, the operator completed a visible graphical character challenge using the normal UI. The dialog disappeared, restaurant search resumed (`29-restaurant-results.png`), and the target Xianshanji restaurant details opened without a verification overlay (`30-store-open.png`). This was manual foreground recovery, not an Agent result.

The queue page (`32-queue-loaded.png`) reported that the phone was too far from the restaurant to obtain a queue ticket. The 3–4-person table category showed no queue at that moment. No booking entry was observed on the inspected details and queue pages. Queue availability is not a reservation for the requested afternoon. No queue ticket, reservation, order, or payment was submitted.

A separate capture defect was confirmed: the cached image retained an older puzzle while SurfaceFlinger showed the current one. Candidate `53b0446` preserves the final frame of a short update while retaining the 5 fps processing limit. It passed 16 host assertions and compilation and was deployed through a temporary runtime bind mount. Pixel validation passed three 120 ms short-scroll tests on a Settings list: after each animation stopped, both direct SurfaceFlinger captures and the cached PNG had identical RGB pixels. Frame sequences advanced from 8 to 13, 18, and 23. A static re-export kept sequence 23 and increased frame age, rather than pretending to contain a new frame. The restarted independent display is ID 3. This does not constitute full ROM acceptance.

Screenshots are local evidence under `out/manual-screen-case-20261026/manual-verification-20261026/`. Structured status is maintained in [acceptance.json](acceptance.json).

## Run 04 after the frame fix

The real power-key Assist image produced a new hint from the WeChat scene. After accepting it, the phone Agent reached an unsubmitted booking review at the alternative Haidilao Longyang Plaza restaurant for October 26 at 14:00, 3 people. This alternative has not been approved by the human; the nonspicy mushroom-pot request is a note, not a restaurant guarantee. The original mushroom-hotpot specialist was not booked.

The Agent then retrieved a real Amap route from the explicitly labeled Century Avenue station demo origin: line 2, three stops, currently estimated at 12 minutes and CNY 3. This is not an afternoon timetable. The fixed six-minute broker deadline interrupted the final response and left the task failed. Thus the review/route milestones passed, while clean completion and a repeatable showcase did not.

Run 04 evidence remains local under `out/manual-screen-case-20261026/run-04/`. The booking review contains an account-prefilled phone number and must not be published to the public documentation site.

Candidate `b67ffff` raises the fixed execution deadline to 15 minutes without renewing it on progress. Cancellation, stop confirmation, and cleanup remain unchanged. The broker compiled, passed independent review, and was installed on the Pixel with its service and SHA verified. A complete business rerun and real 15-minute deadline test have not yet been performed.
