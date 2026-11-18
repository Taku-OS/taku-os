#!/usr/bin/env python3
"""Add Taku's local Frida server and Java bridge defaults to REA's dist tree."""
from __future__ import annotations

import pathlib
import re
import sys

FILES = ("dist/frida/FridaInstrumentationManager.js", "dist/mcp/createServer-*.js")


def replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    match = re.search(pattern, text, flags=re.MULTILINE)
    if not match:
        raise SystemExit(f"patch anchor not found: {label}")
    return text[: match.start()] + replacement + text[match.end() :]


def patch(path: pathlib.Path) -> None:
    text = path.read_text()
    if "#defaultRemote()" not in text:
        pattern = r'(?m)^(?P<i>[ \t]+)listDevices\(remote\) \{\n(?P=i)[ \t]+return this\.#withOperation\("list_frida_devices", \(\) => this\.#listDevices\(remote\)\);\n(?P=i)\}'
        match = re.search(pattern, text)
        if not match:
            raise SystemExit(f"list_frida_devices anchor not found in {path}")
        indent = match.group("i")
        replacement = "\n".join([
            f'{indent}#defaultRemote() {{',
            f'{indent}\tconst address = process.env.REA_FRIDA_REMOTE?.trim();',
            f'{indent}\treturn address ? {{ address }} : undefined;',
            f'{indent}}}',
            f'{indent}listDevices(remote) {{',
            f'{indent}\treturn this.#withOperation("list_frida_devices", () => this.#listDevices(remote ?? this.#defaultRemote()));',
            f'{indent}}}',
        ])
        text = text[: match.start()] + replacement + text[match.end() :]

    if "const effective = remote === undefined ? selector" not in text:
        pattern = r'(?m)^(?P<i>[ \t]+)listProcesses\(selector\) \{\n(?P=i)[ \t]+return this\.#withOperation\("list_frida_processes", \(\) => this\.#listProcesses\(selector\)\);\n(?P=i)\}'
        match = re.search(pattern, text)
        if not match:
            raise SystemExit(f"list_frida_processes anchor not found in {path}")
        indent = match.group("i")
        replacement = "\n".join([
            f'{indent}listProcesses(selector) {{',
            f'{indent}\tconst remote = selector.remote ?? ((selector.deviceId === undefined || selector.deviceId === "local") ? this.#defaultRemote() : undefined);',
            f'{indent}\tconst effective = remote === undefined ? selector : {{ ...selector, deviceId: undefined, remote }};',
            f'{indent}\treturn this.#withOperation("list_frida_processes", () => this.#listProcesses(effective));',
            f'{indent}}}',
        ])
        text = text[: match.start()] + replacement + text[match.end() :]

    if "const effective = remote === undefined ? input" not in text:
        pattern = r'(?m)^(?P<i>[ \t]+)startSession\(input\) \{\n(?P=i)[ \t]+return this\.#withOperation\("start_frida_session", \(\) => this\.#startSession\(input\)\);\n(?P=i)\}'
        match = re.search(pattern, text)
        if not match:
            raise SystemExit(f"start_frida_session anchor not found in {path}")
        indent = match.group("i")
        replacement = "\n".join([
            f'{indent}startSession(input) {{',
            f'{indent}\tconst remote = input.remote ?? ((input.deviceId === undefined || input.deviceId === "local") ? this.#defaultRemote() : undefined);',
            f'{indent}\tconst effective = remote === undefined ? input : {{ ...input, deviceId: undefined, remote }};',
            f'{indent}\treturn this.#withOperation("start_frida_session", () => this.#startSession(effective));',
            f'{indent}}}',
        ])
        text = text[: match.start()] + replacement + text[match.end() :]

    if "const bridgeSource = bridgePath ? await readFile" not in text:
        pattern = r'(?m)^(?P<i>[ \t]+)const nativeScript = await managed\.session\.createScript\(scriptSource(?:, \{ name: `rea-\$\{scriptId\}` \}|, \{\n(?:(?P=i)[ \t]+name: `rea-\$\{scriptId\}`,\n)?(?P=i)\})\);\n'
        match = re.search(pattern, text)
        if not match:
            raise SystemExit(f"createScript anchor not found in {path}")
        indent = match.group("i")
        replacement = "\n".join([
            f'{indent}const bridgePath = process.env.REA_FRIDA_JAVA_BRIDGE?.trim();',
            f'{indent}const bridgeSource = bridgePath ? await readFile(bridgePath, "utf8") : null;',
            f'{indent}const nativeScript = await managed.session.createScript(bridgeSource ?? scriptSource, {{',
            f'{indent}\tname: `rea-${{scriptId}}`,',
            f'{indent}}});',
        ]) + "\n"
        text = text[: match.start()] + replacement + text[match.end() :]

        load_pattern = r'(?m)^(?P<i>[ \t]+)nativeScript\.message\.connect\(onMessage\);\n(?P=i)[ \t]*if \(!signal\?\.aborted\)\s*await nativeScript\.load\(\);'
        match = re.search(load_pattern, text)
        if not match:
            raise SystemExit(f"load anchor not found in {path}")
        indent = match.group("i")
        replacement = "\n".join([
            f'{indent}nativeScript.message.connect(onMessage);',
            f'{indent}if (!signal?.aborted) {{',
            f'{indent}\tawait nativeScript.load();',
            f'{indent}\tif (bridgeSource !== null) {{',
            f'{indent}\t\tconst result = await nativeScript.exports.run(scriptSource);',
            f'{indent}\t\tif (result && result.ok === false) throw new Error(result.error || "Frida script failed");',
            f'{indent}\t}}',
            f'{indent}}}',
        ])
        text = text[: match.start()] + replacement + text[match.end() :]

    path.write_text(text)
    print(f"patched {path}")


def main(argv: list[str]) -> None:
    if len(argv) != 2:
        raise SystemExit("usage: patch-rea-frida.py REA_ROOT")
    root = pathlib.Path(argv[1])
    matched = []
    for pattern in FILES:
        matched.extend(sorted(root.glob(pattern)))
    if not matched:
        raise SystemExit("no REA dist files found")
    for path in matched:
        patch(path)


if __name__ == "__main__":
    main(sys.argv)
