# PR #60 follow-up: Live Talk ownership and hidden voice pages

Addresses [review 5920523717](https://github.com/Taku-OS/taku-os/pull/60#pullrequestreview-5920523717)
on `324890e` (three inline threads, two regression groups).

- Remove passive thread-mismatch stopping from `useLiveTalk`. Null/empty startup remains active;
  an empty page adopts the lazily created thread. An already mounted old conversation cannot
  stop or redirect the native Keep talking handoff. Explicit history/new-chat actions stop Live
  Talk, as do the existing Home/collapse actions.
- Screen-off cancels voice delivery and recording centrally, including Continuous mode. The
  periodic loop checks interactive/keyguard state before restarting. Automatic start failures
  are caught and reflected in session state; cancellation exceptions still propagate normally.
- Home and cross-app Overlay stop latched Listening when actually hidden, including pending
  finalization. Matching gesture IDs protect newer sessions from a stale overlay. Rotation and
  Continuous handoff are preserved; transient focus loss alone does not cancel latched speech.
- Existing Agent commands use the same lifecycle and state. The preinstalled command reference
  documents the awake/unlocked condition and background cancellation.

[Acceptance JSON](acceptance.json) records 382 frontend tests, five new executable hook cases,
Android unit tests, the signed production APK hash and the three device-probe reports. The
background probe actually opens Settings and sleeps/wakes the assigned emulator; its audio is a
recording double and it submits no model messages. Agent provider state is checked against the
same GUI session after lifecycle cancellation.

The production APK is built separately from the unminified instrumentation target and restored
after probes. This follow-up does not claim another full-ROM build, physical-device acceptance,
or a repeat of the earlier live FAL/Agent end-to-end check.
