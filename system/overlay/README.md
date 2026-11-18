# Taku OS Settings RRO

Original resource overlays and manifest retain existing Android package names and overlay policy, changing only the Settings name and experimental branding.

```sh
bash system/overlay/build.sh
```

Output is the ignored `out/AgentOSLabSettingsOverlay.apk`. The public platform test key is downloaded from official AOSP sources as needed. This test identity is only for matching development images, not production. `deploy.sh` installs and restarts an experimental AVD; this migration only built the APK and did not run it.

Requires SDK android-36, build-tools 36.0.0, and JDK 17+. Full ROM product configuration, device trees, production signing, and cold-start acceptance belong to subsequent image work. Building the RRO APK does not establish a completed ROM build.
