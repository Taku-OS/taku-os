# Operating-system version-number research

Checked 2026-10-25. Covers Android, Apple systems, Windows, ChromeOS/Chromium, Ubuntu, Samsung One UI, and original SemVer text. Records verifiable rules/release examples; TakuOS decisions are in [versioning and releases](../versioning.md). Examples explain numbering, not latest versions.

## No universal version-bump formula

Product numbers may express platform generation, release time, or experience; API, build identity, patch date, device, and channel need separate fields. Vendor cadences differ: `x.y.z` does not imply strict SemVer, and incremental OTA need not increment PATCH.

| Platform | Numbering | Major/minor basis | TakuOS lesson |
| --- | --- | --- | --- |
| Android | Product, SDK/API, QPR, Build ID, security patch | Major SDK may change compatibility; Minor adds APIs; QPR need not change major | Separate product/API/build/patch date |
| iOS / macOS | Product and internal build; two/three visible parts | Major experience changes; release notes distinguish feature/maintenance updates, not strict API contract | Define compatibility explicitly |
| Windows | Name, `YYH1/YYH2`, OS Build/revision, KB | Annual feature releases versus cumulative quality updates | Separate name/release/build/patch |
| Chromium / ChromeOS | Four-part browser version and OS build | Milestones separate from build/branch revisions; MAJOR/MINOR also scheduling | MAJOR counts are not API-break counts |
| Ubuntu | `YY.MM[.POINT]`, LTS, package versions | Calendar releases; point updates collect maintenance/new-hardware support | `24.04` means year/month, not breaking/feature counts |
| One UI | Product version alongside Android | Major redesigns; `6.1` and `6.1.1` also add AI | Third part is not universally fixes-only |

## Android main platform and Minor SDK

[AOSP build numbers](https://source.android.com/docs/setup/reference/build-numbers) lists Android 17/API 37, Android 16/API 36, Android 16 QPR2/API 36.1. Build ID `PVBB.YYMMDD.bbb[.Cn]` may date branch/sync rather than compilation; it differs from visible Android version and TakuOS build format.

Google [SDK cadence, 2024-10-31](https://android-developers.googleblog.com/2024/10/android-sdk-release-update.html) planned major/minor SDKs for 2025. [Android 16 QPR2 release, 2025-12-02](https://android-developers.googleblog.com/2025/12/android-16-qpr2-is-released.html) confirmed the first Minor SDK, new APIs and `SDK_INT_FULL`, while noting security/accessibility behavior changes. Minor does not mean no behavior changes.

Security patch level is separate: [Android Security Bulletins](https://source.android.com/docs/security/bulletin/asb-overview). Repackaging does not prove new fixes were merged.

**TakuOS assessment:** independently assess Android baselines. Triggering TakuOS MAJOR on main-platform upgrades is our ambiguity-reducing choice; ordinary QPRs depend on features/compatibility impact.

## Apple versions and examples

[iOS 26 introduction, 2025-06-09](https://www.apple.com/newsroom/2025/06/apple-elevates-the-iphone-experience-with-ios-26/) describes a major design/intelligence update. [Update history](https://support.apple.com/en-us/123075) shows connectivity fixes in `26.0.1`, features in `26.1`, hardware support in `26.2.1`. Maintenance often fixes issues, but three parts do not guarantee feature-free PATCH releases.

Apple defines [three-part versions](https://developer.apple.com/help/glossary/version-number/) for **app bundles** and distinguishes display version/build string in [distribution guidance](https://developer.apple.com/documentation/xcode/preparing-your-app-for-distribution). This app rule is not a SemVer contract for every Apple OS.

**TakuOS assessment:** understandable three-part products with separate builds; our PATCH explicitly contains compatible fixes, without adopting every vendor exception.

## Windows annual versions and quality updates

[Windows 11 releases](https://learn.microsoft.com/en-us/windows/release-health/windows11-release-information) shows `25H2`, OS builds such as `26200.x`, KB, dates, support periods. `25H2` is release timing, not SemVer MAJOR/MINOR.

[Update types](https://learn.microsoft.com/en-us/windows/deployment/update/get-started-updates-channels-tools) separates feature releases from cumulative quality updates with security/non-security fixes. Delivery mechanisms do not replace categories; rollout channels are not build/product versions.

**TakuOS assessment:** borrow layered identity/channel rollout, without another annual product scheme for the current small-system team.

## ChromeOS and Chromium build layers

[Chromium Version Numbers](https://www.chromium.org/developers/version-numbers/) defines `MAJOR.MINOR.BUILD.PATCH`: MAJOR/MINOR also reflect milestones/schedules, BUILD/PATCH exact code. It describes Chromium OS platform/branch numbers and requires Chromium MAJOR increases for incompatible persisted-user-data changes.

**TakuOS assessment:** separate generation/artifact and explicitly promise data compatibility. Early Chromium OS examples support numbering architecture, not current cadence/support claims.

## Ubuntu calendar versions

[Ubuntu rules](https://documentation.ubuntu.com/project/release-team/ubuntu-releases/) uses `YY.MM[.POINT-RELEASE] [LTS]`: initial year/month, accumulated fixes/new-hardware support for fresh installs, and support class.

**TakuOS assessment:** separate dates/LTS/semantic versions; no unresourced LTS-year promises or relabeling periodic repacks as major systems.

## Samsung One UI counterexamples

[One UI 6.1, 2024-04-15](https://news.samsung.com/global/one-ui-6-1-brings-galaxy-ai-from-s24-series-to-even-more-galaxy-devices) extends Galaxy AI; [One UI 7, 2025-03-18](https://news.samsung.com/us/samsung-one-ui-7-announces-official-rollout-starting-from-april-7-2025/) emphasizes design/interaction changes.

[One UI 6.1.1, 2024-09-05](https://news.samsung.com/us/samsung-one-ui-6-1-1-expands-latest-galaxy-ai-features-to-even-more-users-around-the-world/) adds AI despite a third-part change; some devices show that update as `6.1`. Product/device rules differ, so three-part appearance is no global standard.

**TakuOS assessment:** one target-version meaning, with device/build/capability scope in releases, without One UI numbering exceptions.

## SemVer boundaries

[SemVer 2.0.0](https://semver.org/spec/v2.0.0.html) defines incompatible changes, compatible additions/fixes around declared public APIs. Suitable for SDK/protocol compatibility; OS upstream, firmware, user data, interaction, and release eligibility require additional rules.

TakuOS chooses three-part product cores and explicit compatibility, with separate build/channel/candidate phase. This neither labels other systems strict SemVer nor replaces OTA source identity.

## X search and verification limits

Configured X API official-Android history search returned **HTTP 402, insufficient credits**, without top-up. Public search found originals, but web reads were empty/403 and Android browser loading failed. These are only **unverified original-post indexes**, not fully read technical evidence.

| Original | Topic | Reliable alternative |
| --- | --- | --- |
| [Android Developers](https://x.com/AndroidDev/status/1958264506640994614) | Minor SDK/QPR2 | Contemporary official Google SDK-cadence/QPR2 docs |
| [Windows Update](https://x.com/WindowsUpdate/status/2104983032482717978) | Annual release | Microsoft Learn releases/update types |
| [Carl Pei](https://x.com/getpeid/status/1927831603511779806) | Apple-number comprehensibility | Community lead only, not official Apple evidence |

Queries: `AndroidDev minor SDK`, `WindowsUpdate 25H2`, `iOS 26 numbering`, `semver operating system`, plus X/Twitter domain variants. Snippets, mirrors, and X summaries are not vendor specifications; conclusions use official sources above and repository code.

## TakuOS alignment

At 2026-10-25, [branding](../../image/product-branding.json) separated name/`1.0`, but generation/OTA checks accepted only two parts. On 2026-10-26, implementation added three-part versions, historical two-part reads, separate display, targeting `1.0.1`. Current scope follows [versioning](../versioning.md) and [verification](../reviews/versioning-2026-10-26/README.md), without rewriting old image identities.

[I→J hardware acceptance](../reviews/takuos-1.0-ota-2026-10-25/README.md) changed only build number with identical inputs, not a released fix missing `1.0.1`. New fixes need `1.0.1`, compatibility, actual builds/upgrades; do not relabel old packages.
