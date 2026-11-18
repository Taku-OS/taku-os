# Independent networking for the personal Pixel

The selected client is FlClash 0.9.0 (a mihomo client). The official ARM64 APK URL, SHA-256, and size are pinned in `image/runtime-assets.lock.json`. Run `python3 scripts/prepare-image-assets.py` to obtain and verify the public package. It is an ordinary app retaining its upstream signature, with no platform signature or system UID.

`TAKU_PREINSTALL_PERSONAL_PROXY := true` enables preinstallation; the isolated personal Pixel build entry point sets this value. It is disabled by default for the generic product. FlClash 0.9.0's first-run notice explicitly prohibits commercial bundled sales; this configuration serves only the current personal phone and does not establish a commercial distribution plan.

Subscriptions, node keys, and Agent login data stay exclusively in their respective private phone data directories, never in APKs, source code, images, or reports. First use still requires importing personal configuration and confirming Android VPN authorization. This phone's existing subscription URL returned HTTP 403 on both phone and computer, so it temporarily uses a local configuration verified with real network access; automatic subscription updates have not been restored.

The Agent's native runtime reads `/data/local/agentos/network-proxy`, currently `http://127.0.0.1:7890`. This points to FlClash on the phone, without a computer IP, ADB reverse, or USB networking. When the launcher first detects the client and no private proxy setting exists, it writes this local default; existing settings are preserved.

Phone settings enable always-on VPN, a background battery-optimization exemption, and app autorun. VPN bypass remains allowed, and “Block connections without VPN” is disabled so captive-portal login on hotel and similar networks can still work. FlClash's port must match the Agent's private proxy address.

Routine Agent UI changes can be delivered through APK updates with the same signature and a higher version code. Changes to the base system, kernel, system services, or partitions still require the corresponding image/OTA build. No signed OTA service, download channel, or automatic phone system-update channel is deployed. These changes did not rebuild or flash a complete ROM. The current development device uses OverlayFS to update system components; revalidate against the build and device baseline before a formal OTA.

Official sources:
- https://github.com/chen08209/FlClash
- https://github.com/chen08209/FlClash/releases/tag/v0.9.0
- https://github.com/MetaCubeX/ClashMetaForAndroid
- https://github.com/KaringX/clashmi
- https://github.com/KaringX/karing
