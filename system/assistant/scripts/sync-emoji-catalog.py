#!/usr/bin/env python3
"""Regenerates assets/emoji/emoji-catalog.tsv from Unicode's emoji-test.txt (UTS #51).

Message reactions (MessageReactions.kt) may use any fully-qualified, RGI emoji the phone's font
renders. Each output line is `group<TAB>subgroup<TAB>emoji<TAB>name<TAB>toned`, where `toned` is 1
for skin-tone variants (accepted from Agents, never offered to Jev, which uses the default form).
The Component group (bare skin tones and hair styles) is left out.

Usage: scripts/sync-emoji-catalog.py [path-to-emoji-test.txt]   (default: download VERSION)
"""
import os
import sys
import urllib.request

VERSION = "18.0"
URL = f"https://www.unicode.org/Public/{VERSION}.0/emoji/emoji-test.txt"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets", "emoji", "emoji-catalog.tsv")
TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}


def main() -> None:
    if len(sys.argv) > 1:
        with open(sys.argv[1], encoding="utf-8") as source:
            text = source.read()
    else:
        with urllib.request.urlopen(URL) as response:
            text = response.read().decode("utf-8")
    if f"# Version: {VERSION}" not in text and len(sys.argv) == 1:
        sys.exit(f"expected Emoji {VERSION}")
    group = subgroup = ""
    rows = []
    for line in text.splitlines():
        if line.startswith("# group: "):
            group = line[9:].strip()
        elif line.startswith("# subgroup: "):
            subgroup = line[12:].strip()
        elif "; fully-qualified" in line and group != "Component":
            emoji, name = line.split("#", 1)[1].strip().split(" ", 1)
            name = name.split(" ", 1)[1]  # drop the "E15.0" version tag
            toned = "1" if any(ch in TONES for ch in emoji) else "0"
            rows.append("\t".join((group, subgroup, emoji, name, toned)))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as out:
        out.write(f"# Unicode Emoji {VERSION}, emoji-test.txt; regenerate with scripts/sync-emoji-catalog.py\n")
        out.write("\n".join(rows) + "\n")
    print(f"{len(rows)} emoji -> {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
