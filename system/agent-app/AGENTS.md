This is an Expo/React Native mobile application. Prioritize mobile-first patterns, performance, and cross-platform compatibility.

## Expo has changed — do not trust your training data

Expo ships breaking changes every SDK release. APIs you remember are likely renamed, moved, or removed. Before writing any code that touches an Expo, EAS, or React Native API:

1. Read the major version of the `expo` package in `package.json`.
2. Fetch the matching versioned docs: `https://docs.expo.dev/versions/v<major>.0.0/`
3. For anything else, fetch https://docs.expo.dev/llms.txt — an index of all Expo docs with corrections to common LLM misconceptions. Follow its links to the specific page you need; never answer from memory.

## Commands

Use `bunx` instead of `npx` if the project uses bun (`bun.lock` present).

```bash
npx expo install <package>  # ALWAYS use instead of npm/yarn/pnpm/bun add — resolves SDK-compatible versions
npx expo start              # start the dev server
npm run lint                # ESLint over the whole package (expo lint skips scripts/ and plugins/)
npx tsc --noEmit            # typecheck
npm run doctor              # expo-doctor (pinned devDependency)
npm run audit:deps          # npm audit + registry signatures
npx expo install --fix      # fix incompatible package versions
```

Run lint and typecheck before declaring any task done.

## Navigation & Routing

- Use **Expo Router** for all navigation. Routes live in `src/app/` — every file there is a screen, `_layout.tsx` files define navigators. Keep non-route code (components, hooks, utils) outside `src/app/`.
- Import `Link`, `useRouter`, and `useLocalSearchParams` from `expo-router`. In components, navigate with `useRouter()`; SDK 58's module-level `router` throws before the first render.
- Docs: https://docs.expo.dev/router/introduction.md

## Taku Android build and runtime boundary

Use an independent AVD with hardware GPU enabled, specify its adb serial
explicitly, and leave other tasks' worktrees and emulator instances untouched.
Wait for host load to settle before performance comparisons and record the load
alongside the results.

Build this system companion locally with `npm run build:android`. Its release APK
must use the same development certificate as the system assistant to access the
signature-protected gateway. EAS is optional and is not part of this project's
required workflow. Do not upload signing material or device data to a build service.

Use an ordinary application UID. All privileged execution, authoritative session
state, approvals and workspace ownership remain in the existing native runtime.
Never replace a failed connection with demo conversation data. Chat and task views
must project the same thread; hiding the interface must not stop its task.

Use the imported `../assistant/design-system` tokens and interaction contract.
Device acceptance is scoped to the assigned emulator. Pixel and full-ROM results
must be verified independently. Keep generated Android changes in config plugins.

## Rules

- If `ios/` and `android/` directories do not exist, they are generated (Continuous Native Generation). Never create or edit them by hand — configure native behavior in `app.json` and config plugins.
- Expo Go only includes its bundled native modules. After adding a library with native code, the app needs a development build: `npx expo run:ios|android` locally, or `eas build --profile development`.
- Prefer recommended Expo modules over third-party libraries, and check your available skills before adding dependencies. Docs: https://docs.expo.dev/versions/latest/index.md
