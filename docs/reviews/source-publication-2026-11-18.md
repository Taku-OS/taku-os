# English source snapshot review — 2026-11-18

This review accompanies the source snapshot prepared for `Taku-OS/taku-os-release`. It is based on `Taku-OS/taku-os` main at `f2e138cd48b41da543ddd7765736e75c474d766f`, with the validated English publication, Audio rebuild and dependency-policy changes incorporated. The new destination is private and was initialized with an empty commit. Publishing source does not establish clearance to distribute all assets or a complete ROM.

The release snapshot is submitted through a pull request as one commit on top of the destination's empty initialization commit, authored and committed by `HotBlackTeaLatte`. Squash merging this pull request preserves that initialization commit and adds the source snapshot, resulting in two commits on `main`. The snapshot contains tracked source files, without importing the source repository's Git history, branches, tags or pull-request references. Earlier CI results apply to the reviewed source content; they are not status checks on this pull request. CI must run again for the new commits. The scoped findings and remaining limitations are documented below.

This is a source preparation and scoped security review, **not a clean security bill, full dependency SBOM, penetration test, or fresh device/ROM acceptance**. Existing artifact locks and historical results remain dated evidence. The original repository's history, tags, downloaded dependencies, build outputs, signing files, and local device state are not part of this snapshot.

## Language and regional content

Standalone authored documentation, default product copy, errors, prompts, comments, and generated capability descriptions were translated into English. The capability catalog, Markdown guide, HTML page, and generated examples were regenerated together. Translation preserves commands, protocol identities, acceptance limits, and historical artifact hashes. Both native and RN conversation projections accept the old Chinese and new English attachment envelopes. Memory context/date parsing likewise accepts historical Chinese and English forms; host checks cover both.

The following remain intentionally present:

| Material | Reason and boundary |
| --- | --- |
| Explicit Chinese/Japanese document variants and paired localized UI/resources | Maintained alternatives, not accidental single-version prose. Root README is English; Memory's Chinese README has an English counterpart. Background-workspace and Feed API documents also have explicit English counterparts |
| Historical raw JSON, logs, screenshots, video, and reproduction inputs | They record actual Chinese-language observations or synthetic regression data. Rewriting them would misrepresent execution. English reviews explain the results, failures, and scope; the [historical language register](HISTORICAL-LANGUAGE.md) records preserved source bytes and the English counterpart of the hash-indexed compatibility report |
| Chinese NLP/privacy patterns, persisted Memory enums, legacy names and markers, Unicode/input fixtures | Behavior and stored-data compatibility; these are not newly introduced untranslated display copy |
| `bge-small-zh-v1.5` and its prescribed query prefix | Existing pinned on-device embedding model. Translating UI does not replace the model or establish equivalent English retrieval quality |
| Audio protocol `language` and `targetLanguage` defaults of `zh-CN` | Existing speech/translation behavior in `AudioRequest`, retained alongside supported English. English UI copy does not mean all speech requests now default to English; changing these defaults requires coordinated caller and device validation |
| China Telecom carrier defaults for SIM operators `46003`/`46011`, carrier ID `2237` | Deliberate Pixel 9 VoLTE compatibility, not a general language/default-region setting |
| Real provider/app/model names, source URLs, places, time zones, and Chinese speech samples | Factual provenance and supported functionality. DeepSeek, WeChat, Amap, Chinese speech recognition/input, and regional compatibility records were not relabeled as different products or test runs |

The snapshot therefore does **not** claim to contain no Chinese characters or no China-related dependencies. The review distinguishes accidental default copy from intentional localization, compatibility behavior, and evidence.

## Credentials and content exposure

Gitleaks 8.30.1 was checked against the checksum already pinned in CI. The baseline source scan produced 47 generic-key candidates: 40 content digests/public certificate fingerprints, two Figma document identifiers, two synthetic credential fixtures, and three function-call false positives. No candidate was confirmed as a live credential.

[The scanner configuration](../../.gitleaks.toml) narrowly exempts the reviewed exact values at exact paths, only for the generic detector; other detectors remain enabled. Regression tests verify that a changed value, or the same value at another path (including a nested copy of the reviewed path), is still detected. The initial-commit CI blind spot was fixed: when there is no previous SHA, the scanner checks the complete reachable commit instead of an empty `HEAD..HEAD` range. Six scanner/scope tests pass with the pinned scanner.

A final scan covers the complete snapshot commit before upload. Local redacted scanner output and verification logs are retained outside the committed source. These checks do not establish the absence of every possible secret or sensitive datum.

All 462 tracked bitmap images were processed with English OCR, with no OCR execution failures. Three password/API-key label candidates were visually inspected: Android settings labels, a BYOK settings screen, and a masked-key view showing only the final four characters. No complete credential was found in those candidates. All three tracked videos were also sampled at seven positions each (21 frames total); English OCR found no credential-label, key-prefix, or email-pattern candidates in those samples. Video metadata contained no location-tag fields. This is not a comprehensive visual review of every image, video frame, personal name, or copyrighted element. Historical screenshots remain actual observations.

An internal conversation identifier, a private Workspace Portal route, a private build-host address, and incidental personal configuration details were removed from authored prose. Synthetic network addresses, emulator networking, public source identifiers, and checksum-bound evidence were retained where meaningful.

## Dependency advisory still open

The user authorized queries to the official npm registry and dependency installation for validation. Installation used lockfiles with install scripts disabled; downloads and caches stayed in ignored local directories. Reviewed repository preparation scripts were run separately for validation.

| Lockfile scope | npm audit result |
| --- | --- |
| `tooling/app-kit/api` | 0 reported vulnerabilities |
| `examples/godmode/rea-provider/frida` | 0 reported vulnerabilities |
| `system/agent-app` | 41 high-severity affected-package entries, all tracing to one underlying picomatch advisory |

[GitHub's GHSA-fc5q-39xc-vjpf / CVE-2026-112366](https://github.com/advisories/GHSA-fc5q-39xc-vjpf) reports regular-expression denial of service from crafted repeated extglobs in picomatch through 4.0.7 and lists **no patched version**. The maintainer disputes the advisory, and [Snyk has revoked its corresponding advisory](https://security.snyk.io/vuln/SNYK-JS-PICOMATCH-21248802), but the official npm audit still reports it. The lock includes 2.3.2 and 4.0.7 through Expo/Metro and related tooling. A production Android JavaScript export completed successfully; inspection of all 1,691 recursively collected source-map entries found no picomatch, micromatch, or tinyglobby modules. This narrows the observed exposure to tooling; it does not prove arbitrary user-supplied glob patterns are safe or replace device acceptance. No forced Expo/React Native downgrade was used.

On 2026-11-18 the user explicitly accepted a temporary exception for this advisory, recorded in [the exact policy](../../system/agent-app/security/npm-audit-exception.json), expiring at **2026-12-18 00:00 UTC**. The finding remains open: the decision records 41 reported and 41 waived entries, not zero vulnerabilities. The policy binds the unchanged lockfile SHA-256 and the accepted findings graph. Every direct advisory field, severity, installed path and dependency edge is retained; only ordering, duplicate edges and npm's variable computed transitive ranges/upgrade advice are normalized. Changed dependencies, new or changed findings, incomplete registry responses and expiry block the check. Full audit coverage and registry signatures remain enabled. Build tooling must not receive untrusted external glob patterns.

CI also creates a fresh production Android export and checks all bundle source maps recursively. The waived packages entering the bundle, missing maps, DOM/executable assets or an unsupported output layout block CI. Complete audit JSON, the explicit exception decision and compact bundle/hash evidence are retained for 30 days as the `agent-app-dependency-audit` artifact. The deadline is enforced on each check invocation, not by automatically invalidating historical runs. Regression tests cover changed locks/advisories, exact expiry, malformed reports, nested maps, extra chunks and DOM assets. This is a bounded risk acceptance with mandatory review before renewal or dependency changes, not a vulnerability fix.

This npm check does not audit every Maven, native, model, kernel, vendor, downloaded runtime, or operating-system dependency.

## Trust and distribution boundaries

- The root God Mode service uses an abstract local socket without a peer-UID check in the reviewed server. Actual ordinary-app reachability depends on device/SELinux behavior and was not tested here. The project's trusted Agent/plugin model and privileges are documented in [SECURITY.md](../../SECURITY.md). No Agent access was removed or represented as a security fix.
- App Kit exposes a JavaScript bridge in its WebView. Navigation/frame-origin reachability needs a dedicated review; bridge presence alone does not prove exploitability. Assistant cleartext networking is limited to loopback, and the App Kit API binds loopback.
- The imported design-system [notice](../../system/assistant/design-system/THIRD_PARTY_NOTICES.md) explicitly describes private team use without an overall open-source grant. Taku/Figma branding, internal-marked generated assets, and other imported materials need distribution-rights resolution. Font/icon licenses do not license the entire design package. The two upstream reference screenshots mentioned in that notice were not included in the Android import; this review does not infer their presence merely from the notice.
- Google/vendor components and complete ROMs have separate distribution restrictions. See the [third-party inventory](../third-party-materials.md) and [source scope](../open-source-scope.md). An Apache-2.0 root license does not override third-party terms.
- Cloud-builder control remains pinned to the original repository ID. It will not automatically operate infrastructure from the destination repository.

## Validation and remaining build work

| Check | Result and scope |
| --- | --- |
| Agent App lint and TypeScript | Passed after final translation/compatibility changes |
| Agent App tests | 406 passed, zero failures/skips, including 17 audit-policy and Android boundary cases, with permitted local subprocess access |
| Agent App dependency checks | Full official-registry audit reports 41 affected entries under the accepted temporary exception; registry signatures, SDK alignment and all 20 Expo doctor checks passed |
| Plugin loader and host suites | Both complete suites passed with JDK 17, including direct hello-world calls and the aggregated MCP bridge with English output assertions |
| Core SDK/protocol, capability inventory, raw-interface inventory, structure/link checks | Passed; all 16 checksum-indexed historical artifacts remain valid |
| Host suites for emulator-runtime tooling, surfaces, proactivity, OTA, publication, island, Audio contracts | Passed; the emulator-runtime suite is host testing, not an emulator boot |
| Assistant host checks | Main reply, gateway, Matter runtime, confirmation, system AI, routing/projection, and chat projection passed; stale expected translated diagnostics were updated |
| Native Assistant APK and broker | Compiled with JDK 17, Kotlin 2.3.20, Android API/build tools 36; no device installation claimed |
| Memory production APK and fixture APKs | Compiled, including English/legacy context parsing and privacy-marker changes; device SQLite/GUI fixtures were not executed |
| Audio source and client | Kotlin/JVM compilation passed with pinned dependencies; contract tests passed |
| Full Audio packaging | Passed: full APK and bridge rebuilt from the English sources with TTS, JDK 17, Kotlin 2.3.20, and Android API/build tools 36; APK signature verified against the existing public AOSP development certificate |
| Audio ROM source lock | Passed: the genuine completed build receipt was verified before regenerating the lock; all 10 Audio ROM tests pass, including source-lock validation |
| Aggregate `npm run verify` | Passed after the genuine Audio lock refresh, using the installed JDK, Kotlin, Android, and Go toolchains |
| AI ROM, source-repair, Agent UI ROM tests | Passed their existing test scope; they do not attest a fresh English Agent App APK |
| Input method | Both patches applied successfully to exact upstream commit `048f581c652367567b8ee5c28c5163b805288895`. Ten changed `patchedFiles` digests were recomputed from actual applied source. Default UI label and AI reply copy are English; explicit Chinese locale resources remain |
| Full input-method artifact | Not rebuilt: the original development signing key and complete native toolchain are unavailable. Historical `rom-input-lock.json` was retained, not reattributed to changed patches |
| Script/diff checks | Edited Python and shell syntax and `git diff --check` passed |

The Audio notice download repair uses [jsDelivr's version-fixed ONNX Runtime LICENSE](https://cdn.jsdelivr.net/gh/microsoft/onnxruntime@v1.28.2/LICENSE) and [ThirdPartyNotices](https://cdn.jsdelivr.net/gh/microsoft/onnxruntime@v1.28.2/ThirdPartyNotices.txt). Both files match the original SHA-256 pins exactly; no notice hash, runtime version, native library, or model asset changed. The rebuilt APK SHA-256 is `27a112b92eae837e6f86872d2d36b9d182cf2c87af11341f35046a5f25eb67ce`; the bridge SHA-256 is `6d22a6e8ec6a2f390d452a239c3a231303d117abaea8b597593163dc7dcfb114`. The refreshed lock covers 93 source inputs and 390 asset paths. All six pinned notices were verified inside the APK; only its `classes.dex` entry changed from the prior lock.

No isolated emulator, physical phone, ROM flash, English end-to-end GUI acceptance, or full English Agent App Android APK build was completed in this environment. The Agent App export above validates its JavaScript bundle only. New model-quality, device-permission, runtime-origin, and firmware claims require their own validation. Rebuild and verify remaining affected release artifacts with their proper signing identities, then refresh locks from actual outputs before producing a ROM from this snapshot.

Public visibility or complete-ROM redistribution should remain a separate decision after the open dependency finding, asset rights, artifact rebuilding, and any required device acceptance have been addressed.
