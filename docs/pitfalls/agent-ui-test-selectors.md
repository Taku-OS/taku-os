# Old UI test script cannot find "Add to home"

[Back to pitfalls](README.md) · [Shared environment reference](emulator-reference.md)

**Environment:** UI test scripts for the Agent drawer

**Symptom:** An old UI test still taps "Add to home" and fails with `UiObjectNotFoundException`.

1. Read the current UI tree again, locate controls by the current page's `content-desc`, and remove the old script's assumption about "Add to home".
2. In the drawer, the conversation input uses the `chat_input` string. The send button uses `chat_send` when idle and `chat_steer` during a run. See [AgentDrawerView](../../system/assistant/src/org/agentos/assistant/AgentDrawerView.java) for the control definitions.
3. After typing, read the input content back, then use the matching button. Check the result against the task or turn actually created.

**Result:** With updated selectors, conversation, follow-up in the same session and adding instructions during a run all pass in this environment. This fixes the test script's selector problem.
