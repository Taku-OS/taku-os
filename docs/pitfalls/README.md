# Taku OS mobile pitfalls

Problems hit during mobile development on Taku OS, with what solved them, so you can go straight to a working fix.

This list only includes problems that were solved and verified, one per row. Detailed steps are in separate Markdown files in this directory, linked from the table.

| Problem | Verified fix | Environment | Steps |
| --- | --- | --- | --- |
| Home cannot access the task directory | Restore the `files` parent directory to system:system / 0700 and restore its file label. Home access and conversation then work. | AOSP userdebug / Home running as system UID 1000 | [Steps](home-task-directory-permissions.md) |
| Java compile fails with missing SourceVersion | Add the matching OpenJDK 12 API to ECJ and convert it to DEX. The original project then compiles. | Android 16 / ECJ 3.33.0 | [Steps](java-sourceversion.md) |
| Compile fails because LambdaMetafactory cannot be resolved | Add the SDK's lambda compile stubs. The unchanged source then compiles. | SDK 36 / build-tools 36.0.0 | [Steps](java-lambda-metafactory.md) |
| Old UI test script cannot find "Add to home" | Update the script to the current content-desc values. Conversation, follow-up messages and added instructions then pass. | UI test scripts | [Steps](agent-ui-test-selectors.md) |

All four entries are `emulator_verified`. Versions and commands are in the [shared environment reference](emulator-reference.md). They do not represent the current main branch or a physical device.

## Adding an entry

1. Check the table and existing files first. If the problem is already there, add to that row and file.
2. Once a new problem is solved and verified, add a `.md` file here with the symptom, the fix, the environment and the result, and note the device, version and date. Include the cause if it is confirmed.
3. Add a row to the table and link the file under Steps. If the result comes from an emulator or physical device, mark it `emulator_verified` or `device_verified`.

Edit the Markdown files in this directory directly, so updates are reviewed with the source.
