# Android diagnostics

CodecListProbe, DecodeProbe, and TextureDecodeProbe are development diagnostics. They are separate from the background-screen runtime and are not automatically deployed with Home / VirtualDisplayHost. Their source was moved from the original video diagnostics experiment without changing it.

```sh
bash tooling/diagnostics/build.sh
# Or: npm run build:android -- --with-diagnostics
```

Requires android-36, build-tools 36.0.0, and JDK 17+. Produces `out/diagnostics.jar` without installation or device operations. When using `app_process`, explicitly select a development device, supply public test media, and follow the probe's source parameters. See the [evidence index](../../evidence/README.md) for historical results. Successful compilation does not establish real-app video compatibility.
