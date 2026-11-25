---
name: taku-app-development
description: Develop or modify applications on Taku Android, including an ordering app from a scanned menu. Choose a route from the actual device tools and the requested app experience.
---

Use the current Taku conversation and existing execution tools. Inspect the Harness environment and actual paths before choosing a route. Do not start another model process.

Before starting app development, mark the current active turn as an app-build workflow using
`content://org.agentos.agent.home-assistant`. Read `assistant/get` for `threadId` and
`turn.turnId`, and confirm they are this conversation/turn; never label another conversation.
Call `assistant/app-build` with `{"threadId":"THREAD","turnId":"TURN","active":true}`.
Only this explicit app-development marker shows the **Building...** compact island. Ordinary
chat, speech recognition, searches, errors and approval waits never show that capsule.
In a final cleanup step call the same method with `active:false`. Turn completion, failure,
interruption, approval waiting and changing turns also suppress the capsule automatically.
The marker affects presentation only; it never starts a build or changes task execution.
If the current UI session is unavailable, continue the actual work without inventing a status.

When the user asks for an ordering app and this turn includes Menu Scan context, follow
[the Menu Scan workflow](references/menu-scan.md). It uses the same installed template for
every menu. Use the attached photo and OCR rows for content, estimate serving nutrition,
and check verified sources for matching posts at the identified branch.

For requests outside the Menu Scan workflow, choose based on the task:
- Lightweight utility or interactive page: when the React app-kit host, esbuild and API service are actually installed, write real JSX and business code. Read the kit's generation-contract.txt and relevant seed interfaces before using them. UI and Agent calls share the business API.
- Native Android components or standalone native APK: use the prepared on-device Java toolchain. Read [native.md](references/native.md) for its invocation and acceptance boundary.
- Doric and HapJS are historical alternatives. Use them only when the required host/toolchain is present and they suit the user request; do not imply that they ship with every Taku image.

Keep source, app data and build artifacts in the selected application's directory. Generate
project-specific business logic for general app development. Preserve existing application data
on requested updates. Distinguish build success, installation, UI interaction and actual
system/API results. A generated ordinary APK does not inherit Agent root permissions.

When tools or dependencies are absent, identify the missing component explicitly and use an available route that still meets the request, or explain the required setup. Do not copy account state or personal signing material. Do not claim every Android API or arbitrary Gradle project is supported.

For native plugin management, the device command `taku-plugin list` exposes the same workspace catalog, version and enabled state as the plugin screen. `taku-plugin status` shows MCP status. Use `taku-plugin install MARKETPLACE_PATH NAME`, `taku-plugin enable ID`, `taku-plugin disable ID`, or `taku-plugin uninstall ID`. Successful mutations reload MCP; exit 2 means the mutation succeeded but reload failed, so inspect state before retrying. Upgrade and rollback are not available. Third-party MCP currently runs with root runtime privileges; only use trusted sources.
