# HTTP image integration

Source commit: `d225a5efdab182f3e3d8c9b6a977fe3cbff95e50`.
Target base: `ee18ea78882d188a5200094efcd92bdcc97d7132` on `vela/TAKU-new-UI`.
The only merge conflict was the changelog; both sets of entries were retained.

The shared Chat/Feed image loader and Feed catalog now accept supplied HTTP and HTTPS
image URLs, including local-network addresses. HTTP SVG uses asynchronous fetching
instead of being cached as a failed bundled asset. The existing manifest plugin enables
cleartext traffic in the release application. Native pin metadata, action URL rules,
local camera inputs and the previously integrated durable Feed remain unchanged.

Validation:

- Agent typecheck, lint and 368 tests passed, including HTTP SVG loading/cache/error cases
  and both supplied-file and model-output generation preserving the URL.
- Repository `npm run verify` passed.
- `npm run build:android` succeeded; the merged release manifest has
  `android:usesCleartextTraffic="true"`.
- Installed the signed release APK on the isolated API 37 AVD `Taku_Audio_ROM_Merge`
  (`emulator-5690`, host GPU). The demo emulator was not changed.
- Published a synthetic image card through `feed/publish`, generated another through
  the existing file CLI, and confirmed `feed/get` returned the identical image UI/URLs.
- A controlled HTTP server supplied a PNG, an SVG via the emulator's `10.0.2.2` host
  route, and an HTTP 302 to another SVG. Unique URL queries prevented cache-only success.
  Server requests and the actual desktop screenshot confirmed all three rendered.
  The isolated AVD first needed connection to its available virtual Wi-Fi.
- Synthetic cards and the test port forward were removed after acceptance.

Local evidence: `out/pr5-images/{card.json,generate-receipt.json,proof.json,rendered.png}`.
APK SHA-256: `9780a4f93f0b8de29b3cd81f52801879e34b10a6508cc34ce355e4d15e7bc146`.
This verifies functional image loading, not performance, real hardware, live model
output, or HTTPS-to-HTTP redirects. Existing HTTPS reference behavior is covered by tests.
