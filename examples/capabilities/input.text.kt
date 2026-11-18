// Text input and input-method bridge · Verified on an emulator (within the cited evidence)
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Obtain the current editor's InputConnection in InputMethodService, or set node text through authorized accessibility
// Expected result: The current editor receives Chinese text; verify the UI/storage before treating the write as successful
// This review did not automatically execute this example; define scenario-specific success criteria first.
currentInputConnection?.commitText("桌面输入测试", 1)
