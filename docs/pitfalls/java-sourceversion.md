# Java compile fails with missing SourceVersion

[Back to pitfalls](README.md) · [Shared environment reference](emulator-reference.md)

**Environment:** Android 16 / ECJ 3.33.0 / native app compile on the device

**Symptom:** `NoClassDefFoundError: Failed resolution of: Ljavax/lang/model/SourceVersion;`

**Cause:** ECJ starts, but real compilation needs a Java compiler API that Android does not ship.

**Verified fix:** Compile an unmodified OpenJDK 12 `SourceVersion` from a pinned commit into the compiler. The JDK 21 module was not used directly, because its initialization calls `Runtime.version()`, which is not in this Android framework.

1. Get [the original SourceVersion.java at a pinned OpenJDK 12 commit](https://github.com/openjdk/jdk12u/blob/89c4c727007ec579d4183221954ed33f23b46bd5/src/java.compiler/share/classes/javax/lang/model/SourceVersion.java), keeping the package name `javax.lang.model`.
2. On the host, compile it with the JDK's `javac --release 8` and package it as `compiler-api.jar`.
3. Use SDK 36 D8 to convert `ecj-3.33.0.jar` and `compiler-api.jar` together to Android DEX. When generating the new `ecj.jar`, keep the non-`.class` resources from the original ECJ package and add all DEX files D8 produces.
4. Deploy the new `ecj.jar` to `/data/local/agentos/light-app-lab/toolchain/ecj.jar` on the device and recompile the original project.

**Result:** SourceVersion is no longer missing. After adding the [lambda compile stubs](java-lambda-metafactory.md), the same source compiles, signs and installs. The source was not changed to work around the error.
