---
name: taku-plugin-creator
description: Turn a completed case, script, or user request into a locally tested Taku plugin and prepare or submit it to the community.
---

Use the current conversation and its artifacts; do not start another model process. Identify the reusable behavior, author, dependencies, declared capabilities and permissions, and remove secrets or personal data. Build a plugin using the native `.codex-plugin/plugin.json` format. Follow [the creation and publishing guide](references/creation.md).

Run `taku-plugin validate`, `pack`, install and exercise the plugin, and uninstall the test installation before publishing. Report actual results, the package and index paths, and any community PR URL or local-only reason. Never claim a PR was created if it was not.
