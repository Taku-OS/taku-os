---
name: taku-figma-motion
description: Recreate a motion reference from a saved Taku Notebook post or user-supplied video as an editable and genuinely playable Figma prototype, in an ordinary Taku conversation. Use when the user asks to remake, reproduce, or try an animation in Figma.
---

# Recreate reference motion in Figma

## Find the reference

- If the user refers to an item they saved, call `taku_phone.notebook_recall` with any title, app, or subject they named. When they say “this” or “the one I just saved,” check the newest matching entry. Use the actual returned ID with `taku_phone.notebook_read_video`. For direct input, use the video or post the user gave. The recall image belongs to the first result. If another result is the reference, narrow the query to that result or use its own video frames.
- Before creating anything, extract a short visual and motion checklist from the actual images: preserve the object's identity and silhouette, the main layout and alignment, visible labels, colors, and the order of the feedback. Use a still for layout evidence when video is unavailable, and report which motion details could not be checked. When the user asks for a recreation, preserve the reference design unless they explicitly request a visual change. Interpret ambiguous speech in context. For a PDF that is shot like a basketball, the draggable object is still the PDF card. Keep the same object, colors, and layout in both the editable frame and the playable version.

## Create the editable design

Confirm the connected account with `figma.whoami` and load the applicable Figma skills. Use the user's explicit Design file and frame if given. Otherwise, create a private Design file after loading the create-file skill. Use the Figma session for that file throughout. Build an editable, top-level frame with `figma.use_figma` and keep its identity for the rest of the task.

### Show live authoring when asked

1. On desktop Figma, open the same Design file under the same Figma account and select the exact blank top-level frame. If this selection requires the user, give them the frame link and wait until it is visible on the phone. Resume with the same frame after an interruption. On the assigned Android screen call `taku_phone.request_apps` with `packages: ["com.figma.mirror"]` and a short localized `taskTitle`, then call `taku_phone.open_app` with only `packageName: "com.figma.mirror"`.
2. In the Android Figma app, open **Mirror** and tap **Begin mirroring** for the named desktop selection. Use `taku_phone.observe_background` to verify that the blank target frame is rendered. A selection banner or loading screen is an intermediate state. After the canvas is visible, if `taku_phone.show_background` is available, use it to open the same assigned screen in Taku's viewer and verify it.
3. Build visible groups in separate `figma.use_figma` calls. After each group, call `taku_phone.observe_background` and check that the new group appeared on the same screen key and generation before writing the next one. Keep the same frame selected. Separate completed writes and observed phone updates are the evidence of staged construction. If Mirror stalls, recover the target selection and check the last written group before continuing.

If the user did not request live viewing, write and verify the editable frame without requiring Mirror. If native writes fail, recover or report what was saved. Use the current conversation's authorized account, destination, and reference. Create work for this request without a prepared conversation or prewritten prototype.

## Create the playable version

For motion that needs continuous pointer tracking or custom physics, create a Code Layer in the same Design file as the editable frame. Start from that frame's actual artwork and geometry to preserve the layout, objects, labels, and colors. Implement the requested gesture, intermediate motion, and feedback. A drag trigger followed by fixed timers cannot demonstrate live aiming. Keep sample upload behavior in the prototype unless the user requests a real upload.

### Upload from Taku's workspace

Read `figma-code-workflows` and the current `figma.send_code_to_figma` schema. Confirm Code Layers are enabled. Create a fresh project under the current Taku workspace with all assets. A self-contained HTML/CSS/JS prototype can use a root `index.html`. Request upload preparation for the same Design file, then archive the project contents at the exact `localArchivePath` that Figma returns. Package and run the upload in the environment that can read that archive. Follow the returned project and archive requirements.

If the returned upload command computes the base64 SHA-256 digest with OpenSSL and Android lacks `openssl`, compute the equivalent digest using whichever tool is present (`openssl`, `python3`, `node`, or `sha256sum` + `xxd` + `base64`) with the returned archive path. Preserve the rest of the scoped upload command, including its destination and form fields. Keep resumable upload data and credentials out of chat and diagnostic output.

```sh
checksum="$(
  if command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 -binary "$archive" | base64 | tr -d '\r\n'
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import base64,hashlib,sys; print(base64.b64encode(hashlib.sha256(open(sys.argv[1],"rb").read()).digest()).decode())' "$archive"
  elif command -v node >/dev/null 2>&1; then
    node -e 'const c=require("crypto"),f=require("fs");console.log(c.createHash("sha256").update(f.readFileSync(process.argv[1])).digest("base64"))' "$archive"
  elif command -v sha256sum >/dev/null 2>&1 && command -v xxd >/dev/null 2>&1 && command -v base64 >/dev/null 2>&1; then
    sha256sum "$archive" | cut -d ' ' -f 1 | xxd -r -p | base64 | tr -d '\r\n'
  else
    echo "MISSING_SHA256_BASE64_TOOL" >&2; exit 1
  fi
)"
```

Continue only after the upload succeeds. Use the returned continuation arguments without changing the upload identity. Preserve the same prompt and Figma file session according to the tool contract. Wait for an import result with the matching Design file and exact Code Layer node. Treat import completion as a saved artifact and verify playback separately. If the current runtime cannot upload the project, report the failed step and what remains in the editable Design file.

## Verify in the Android Figma app

1. On desktop Figma, select the exact imported Code Layer in the same Design file, then use its **Play** control. In the official Android Figma app, open **Mirror** and tap **Begin mirroring** for that Code Layer. If the phone still shows the editable frame, exit its mirror and select the Code Layer's current Mirror target. Check the rendered scene against the editable frame. A generic Recent prototype can show the same visual arrangement as the editable frame, so verify the selected layer by its name and returned node.
2. Exercise the requested gesture in the mirrored Code Layer. For a pull-and-release upload, check that the file follows the finger and the aiming guide changes before release. On a hit, check the file's path through the hoop, the score change, and the upload's progress and completion. If the reference asks for orange feedback, the dashed upload border should fully light while `+1` is visible, then return to neutral. Check that a miss leaves the count and success feedback unchanged. Verify timed feedback in the state where it is visible. Keep the playable view at its starting state when handing it to the user for recording.
3. Return the exact editable-frame link and the playable entry for the imported Code Layer in the same file. State what the phone actually showed, and report any stage or gesture that could not be verified. If a live Mirror session is already set up, continue in the same turn after it displays the correct selection.
