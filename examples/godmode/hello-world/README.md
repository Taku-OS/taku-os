# Hello World plugin

This is the first complete Taku OS God Mode plugin example. It exposes a
`hello_world` tool through the standard MCP `tool-provider` interface. Code runs
on the phone without networking or reading user data.

## Package contents

```text
manifest.json                Plugin identity, capabilities, and launch configuration
server.sh                    Local MCP service
skills/hello-world/SKILL.md  When Agent should call this plugin
build.sh                     Build a distributable ZIP and SHA-256
```

## Installation and invocation

Push the directory to the device and run:

```sh
adb push examples/godmode/hello-world /data/local/tmp/hello-world
adb shell
taku-plugin godmode install /data/local/tmp/hello-world
taku-plugin godmode tools
taku-plugin godmode call-tool org.taku.hello-world hello_world '{}'
```

Expected response:

```text
Hello, World! 👋 from the first Taku OS plugin
```

Disable, re-enable, and uninstall:

```sh
taku-plugin godmode disable org.taku.hello-world
taku-plugin godmode enable org.taku.hello-world
taku-plugin godmode remove org.taku.hello-world
```

## Packaging

From the repository root, run:

```sh
bash examples/godmode/hello-world/build.sh /tmp/taku-hello-world
```

The script creates `org.taku.hello-world-1.0.0.zip` and its `.sha256` file.
The ZIP root contains `manifest.json` and can be passed directly to `install-url` or `install`.

## Development checks

```sh
node sdk/plugins/validate.mjs examples/godmode/hello-world/manifest.json
python3 system/plugins/tests/hello-world-test.py
JAVA_HOME=/path/to/jdk17 bash system/plugins/test-host.sh
```
