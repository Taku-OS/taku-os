# Frida Java bridge bundle

Frida 17 no longer exposes the Java bridge as a global in injected scripts.
`bridge-runner.js` bundles `frida-java-bridge` 7.0.13 and exports `run(source)`
so the REA wrapper can execute ordinary user JavaScript with `Java` available.

Rebuild the committed bundle from this directory:

```sh
npm ci
npm run build
```

The compiled `java-bridge-runner.js` is deliberately committed because the
Android runtime pack must not need a developer toolchain on device.

The build dependency `minimatch` is overridden to 11.0.1 to address GHSA-g3hv-75wg-m249. The existing Frida and Java bridge versions remain pinned. Validate with `npm ci`, `npm audit`, and `npm run build`; the local bundle compile is a host-tool check, not a device instrumentation result.
