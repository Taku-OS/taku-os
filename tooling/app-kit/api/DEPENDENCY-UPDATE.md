# Ajv 6.14.0 security update

2026-10-10: in response to an actual Dependabot alert / GHSA-2g4f-4pwh-qvx6, the direct runtime dependency Ajv was pinned from 6.12.6 to exactly 6.14.0, with updated lockfile tarball integrity. Ajv remains on major version 6; other dependencies, API-server logic, and app API contracts were unchanged.

The official [GitHub Advisory](https://github.com/advisories/GHSA-2g4f-4pwh-qvx6) lists `<6.14.0` as affected and 6.14.0 as fixed. Cross-check the [upstream release](https://github.com/ajv-validator/ajv/releases/tag/v6.14.0) and [v6 backport PR #2588](https://github.com/ajv-validator/ajv/pull/2588).

This service uses `new Ajv({allErrors:true,jsonPointers:true})` with **`$data` disabled**; the advisory requires a dynamic `$data` pattern path. A dependency alert is not evidence that this service was compromised or suffered CPU blocking. The v6 backport handles dynamic-pattern exceptions and does not guarantee bounded execution for arbitrary malicious regexes. Tests do not execute expensive attack expressions.

## Reproducible verification

From the repository root:

```sh
npm --prefix tooling/app-kit/api ci --ignore-scripts --no-fund --no-audit
npm --prefix tooling/app-kit/api test
npm --prefix tooling/app-kit/api audit --omit=dev
```

Tests launch a real localhost HTTP server, copy the existing public scoreboard fixture into an OS temporary directory, and use a random free port with fresh state. On exit they stop their server and delete temporary app files. They use neither Android notifications nor phone operations.

| Check | Recorded passing result |
| --- | --- |
| Ajv backport | Installed 6.14.0; with `$data` enabled, a small invalid regex returns validation failure instead of throwing, while a small valid regex works |
| HTTP entries | Health, app discovery, method descriptions, and OpenAPI |
| Arguments/errors | Invalid JSON/envelopes, wrong type/zero/fraction/team/missing/extra fields, unknown methods/apps |
| State writes | Positive/negative additions and actual score/history reads |
| Idempotency | Same key and normalized request replay without writing; different request conflicts with 409 |
| Undo/failure | Undo, undo replay, and empty-history failure without state/revision changes |
| Transaction failure | Safe-integer overflow returns OUT_OF_RANGE without state/revision writes or reserving the failed key |
| Concurrency/persistence | Five concurrent writes without loss; restart preserves state, revision, and replay receipt |

All 9 Node tests (1 parent + 8 children) passed in that run. Records go to ignored `out/checks/app-api-http-smoke.json`; fresh execution results take precedence. Scope is host HTTP API, not Android, APK, ROM, or hardware acceptance. Existing historical scenario evidence was unchanged.

Ajv's upstream MIT text remains unchanged in [licenses/ajv.txt](../licenses/ajv.txt). Dependency installation directories remain ignored; upstream code is not relicensed.
