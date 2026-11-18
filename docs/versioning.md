# Taku OS versioning and releases

This page defines what each version number covers across the system, builds, components and release channels, and when to make a major, minor or patch release.

**Taku OS product versions use `MAJOR.MINOR.PATCH`, for example `1.0.1`. Each new build also has its own build number. Full and incremental packages are two ways to deliver the same target system, so they do not decide which number changes.**

## Names and identities

| Item | Purpose | Rule |
| --- | --- | --- |
| Product name | Identifies the system to users | Fixed. Never put `1.0` in the name. The `productName` value in [product-branding.json](../image/product-branding.json) is `TakuOS` |
| Product version | Shows the scope of change for users and developers | Three non-negative integers such as `1.0.1`. No leading zeros except `0` itself |
| Build number | Identifies one exact build | 14-digit UTC `YYYYMMDDHHMMSS`. A new candidate gets a new number. A controlled resume of the same candidate keeps its number |
| Android version and API | Upstream platform compatibility | Recorded separately, such as Android 17 / API 37. The Taku OS version never overrides Android or API properties |
| Android security patch level | The patch baseline actually included | Recorded from the real system property. A rebuild does not change it to today's date |
| Channel | Controls the distribution scope | ROM builds currently use `canary`. ROM `stable` is not open, and there is no Beta channel |
| Candidate stage | Shows development maturity | Alpha, Beta and RC describe a stage, recorded separately from the product version, channel and test status |
| APK version | Installs and upgrades a standalone component | Each APK keeps its own `versionName` and `versionCode`, separate from the ROM build number |
| SDK and protocol versions | Declare interface compatibility | The SDK package version, protocol namespace and schemas evolve separately from the ROM |
| Exact artifact identity | Binds the bytes, source and target device | Build number, device, frozen inputs, target-files and OTA SHA, signature trust and AVB together. The product version does not replace these |

Product versions compare as integer tuples, so `1.10.0 > 1.9.0`. Do not sort them as strings or treat them as decimals. The build entry point assigns build numbers. Do not reuse an identity for different content, and do not add separate timestamp numbering in individual modules.

## When to bump a version

When a release contains several kinds of change, use the highest level. A major bump resets minor and patch to zero, and a minor bump resets patch to zero. Commit count, package size, build time and download format do not affect the version.

| Type | Condition | Example | Release requirements |
| --- | --- | --- | --- |
| MAJOR | A new major Android platform, or a breaking change to a committed public interface, extension behavior or persistent data contract | `1.4.3 → 2.0.0`, Android 17 → Android 18, removing a supported task API | Document the platform or interface migration, affected devices, data and recovery plan, and complete platform-level regression tests |
| MAJOR | A new generation of the system experience, approved by the maintainers, that reworks several core entry points and main interactions | A new home screen, task system and system interaction model together | State the scope. A local UI change is not a new generation |
| MINOR | New user-facing capabilities, workflows or extension points that keep existing use and interfaces compatible, or the start of a public deprecation | `1.0.2 → 1.1.0`, a new cross-app decision entry, model service integration or memory capability | Describe the capability and permissions, keep old calls working, and verify old and new paths and data upgrades |
| PATCH | Fixes to existing behavior, security, performance or compatibility, with no new product capability and no broken public contract | `1.0.0 → 1.0.1`, fixes for OTA download, text input, missing permissions, crashes or battery drain | Include a regression check. Image changes still need signature, boot, upgrade and data checks |
| No product version change | Docs, test or CI changes that do not reach system artifacts, or repeated candidates and upgrade experiments for an unreleased version | Two candidate builds of the same `1.1.0` | Assign a new build number, keep the candidate status and do not overwrite the old package |

A major Android platform upgrade triggers a Taku OS MAJOR by this project's convention. Classify ordinary Android QPR, kernel and firmware updates by what actually changed: fixes only are PATCH, new compatible capabilities are MINOR, and broken contracts are MAJOR.

Public compatibility covers the documented SDK, Intent, Binder and tool call contracts, task and extension protocols, user-exportable data formats and existing data retention commitments. New compatible fields should be optional so that old clients can ignore them. Removing a field, changing its meaning or adding a required condition needs an interface migration. A transparent, data-preserving migration of an on-device database does not by itself need a MAJOR.

A major version does not permit wiping data. Normal upgrades aim to keep existing data and settings. Any path that needs a wipe, first-time unlock or signature change must be stated and approved separately, and cannot pass as a normal system update. Emergency fixes still follow compatibility classification and signature and device checks.

### Common changes

| Change | Default | When to go higher |
| --- | --- | --- |
| Fix input candidates, a lost draft after a failed send, or keyboard overlap | PATCH | A new input workflow is MINOR |
| Fix a startup allowlist, system permission declaration or OTA check error | PATCH | A fix must not bypass signature, AVB or data retention checks |
| Adjust colors, alignment or spacing, or restore an existing feature | PATCH | A new entry point or operation is MINOR. Consider MAJOR only for a new generation across core flows |
| First add full Markdown tables, attachment handling or a new model routing capability | MINOR | Fixing existing rendering or routing is PATCH |
| Replace the model gateway implementation while keeping config and interfaces compatible | PATCH | A new user-selectable service or route is MINOR. A broken public config contract is MAJOR |
| Extend the system display SDK with optional fields | SDK MINOR | Once included in the system, the ROM also takes a MINOR. The two numbers need not match |
| Driver or kernel stability fix on an existing device | PATCH | Official support for a new device is usually MINOR, with first install and device tests done separately |
| Change only the build number to test an OTA from source to target | Keep the product version | Candidates and tests only. Do not describe it as a release with fixes |
| A released version needs changed system bytes | At least PATCH | Use a higher level for new capabilities or broken compatibility. Repackaging does not avoid a version bump |

## Candidates and releases

The same unreleased target version can have several builds. Testers need to see the product version, build number, channel and candidate stage together.

| Scenario | Product version | Build number and original package |
| --- | --- | --- |
| Controlled resume of a failed candidate | Keep | Keep the candidate number and the failure state. The existing resume check decides what can resume |
| Candidate fix, rebuild or repeated OTA experiment | May keep the unreleased target | New number and new immutable archive. Keep failed packages |
| Any change reaching the system after release | At least PATCH | New number, new package, new tests. Never replace the released package |
| The same verified candidate promoted to a release channel | Keep | Promote the exact same bytes. Do not rebuild for a channel name |
| Withdrawing from a channel or changing rollout scope | Keep | Change the release status only. Do not alter immutable packages |

Docs can say "`1.1.0`, Beta candidate, build `…`". Do not add `-beta.1` or `+build` to `productVersion` or feeds. Native SemVer prerelease strings would first need support and tests in every consumer. Strict SemVer prerelease ordering applies to packages that declare it, such as the SDK. The ROM uses three core numbers plus a separate stage, channel and build identity, and does not claim full SemVer prerelease support.

`canary`, `stable` and any future Beta are distribution scopes. Existing upstream release types such as `unofficial` are client compatibility fields, so do not put channel names in them. RC means the target version's features are frozen and ready for testing, and does not mean it may ship to `stable`. A `1.x.x` number does not mean every SDK or extension interface is stable. Stability must be declared interface by interface.

## UI and system properties

The UI shows the product name and version separately: the name as the title, the version on its own line, and the build number, Android version, channel and patch date in details. When the name and version appear on one line, keep a space between them. Do not put `1.0` in resource names, package names or product name constants. Generate version constants from a single configuration.

[product-branding.json](../image/product-branding.json) holds separate `productName` and `productVersion` values, which a generator writes to `ro.taku.product.name` and `ro.taku.product.version`. `ro.taku.display.version` and `ro.build.display.id` are derived display and build identities, and must not act as the product version. A display format change must be checked against generated patches, manifest hashes and consumers.

Android `ro.build.version.release` and API, upstream compatibility versions, signature trust, partition identity and package IDs each keep their own role. A Taku OS version change does not need forged upstream versions, renamed package IDs or changed signatures.

### Older two-part versions

For comparison only, the parser treats a historical two-part `1.0` as `(1, 0, 0)`. New versions always output three parts. Old archives, signatures and properties are not rewritten, and this normalization does not make different builds the same OTA source.

## OTA and artifact rules

[AOSP's incremental package rules](https://source.android.com/docs/core/ota/tools) require an exact source build. `1.0.0 → 1.0.1` can offer both a full target package and an incremental package from an exact source. Both install the same target version and identity.

Use the existing `pixel9-image.py` and `pixel9-ota.py` scripts. A release record links the product version, build number, device, Android version and API, patch level, exact source and target, frozen inputs and source commit, package, target-files and incremental SHAs, signature and AVB, size, test scope and channel status.

Upgrade availability depends on the device, exact build, firmware and partitions, signature trust, timestamps and anti-rollback checks. A higher version number does not mean an install can cross ROMs or keep data. A product, Android or patch-level downgrade cannot pass as a higher build number.

To withdraw a bad release, stop distribution first and keep the logs. Prefer a **new fix build** from known-good code. Pointing a channel back at an old package does not mean installed devices can safely downgrade. Any deliberate downgrade, wipe or signature migration needs its own plan.

## Components and interfaces

Android's [`versionName` and `versionCode`](https://developer.android.com/studio/publish/versioning) handle display and install ordering. When APK content changes, assign a higher `versionCode` under that component's rules. The same APK bytes reused in different ROMs need no new number. Do not put the 14-digit ROM build number in an APK's integer `versionCode`.

`0.1.0` in `package.json` belongs to the source SDK and CLI project and is not the phone system version. Each component's source, APK, version number and ROM manifest must stay traceable. A ROM version does not imply that every module was updated.

The SDK and protocol apply [SemVer](https://semver.org/spec/v2.0.0.html) compatibility principles to their own public contracts. A deprecation lists its replacement and removal target. A new SDK keeps at least one MINOR migration window, and emergencies need a written impact statement. Protocol field versions follow the rules in the spec, and `v1` namespaces are not renamed to follow a Taku OS MAJOR.

## Release checks

1. Classify the change against the last official release, and record the target, reason, compatibility scope and update notes. Unreleased experimental builds are not a new baseline.
2. Confirm that the branding source, generated files, APKs and independent SDK and protocol versions changed only where expected.
3. Freeze the build inputs and give each candidate its own immutable identity. Keep old snapshots, failure logs and test data.
4. Verify the full package, payload and AVB signatures with the existing scripts.
5. Test the full target and incremental packages from each supported exact source. On a device, cover install, reboot, target identity, slot switch and snapshot merge, plus data, settings, input method, session and feature regression within the stated scope.
6. Upload immutable objects, check readback, hashes and client eligibility, then update only the approved channel pointer. Candidates that do not qualify are never promoted to `stable` automatically.
7. Check that system update, About phone, system properties, download details and release notes all match the same target. Test the first-install guide separately.
