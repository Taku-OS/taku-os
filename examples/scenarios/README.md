# Candidate scenarios: engineering validation inputs

These are minimal engineering candidates for Ricky/product evaluation. `product_selection=pending`; they do not establish first-release scope, product interaction decisions, or formal acceptance.

- C1: Phone Codex calls the on-device SDK and records Android/ABI/SELinux, displays, and task metadata.
- C2: Phone Codex writes the complete React and business modules for a two-team scoreboard, previews it, builds and installs a standalone APK on-device, and changes scores and undoes actions through its public API.
- C3: Phone Codex operates an existing app on an independent screen while Home remains on the main screen, with native mirror observation/taps.

ADB only deploys experimental inputs, starts phone processes, and collects evidence. App core generation, JSX compilation, APK packaging, signing, and installation must run on Android. Model inference and networking do not run offline on the phone.

Inputs are in `phone-codex-prompt.txt`. Actual results, failures, and recordings are recorded in `../../docs/scenario-acceptance.md`; keep unproven outcomes pending.
