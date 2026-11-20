#!/usr/bin/env python3
"""Curates assets/emoji/reactions.tsv: the 240 emoji Jev may choose for a message reaction.

Source order is Unicode's "Ranked Order by Median Frequency" (https://home.unicode.org/emoji/
emoji-frequency/, 2021 data), filtered for chat reactions: faces, hearts, hands, celebration and
a few hand-picked nature, food, activity and life-event emoji; no flags, keycaps, arrows,
transport, places, religious or gendered variants, and nothing crude, hostile or alarming
(DENY). The ranking predates Emoji 11.0, so newer chat staples (NEW) are placed near the top by
hand. Names come from assets/emoji/emoji-catalog.tsv (scripts/sync-emoji-catalog.py). On the
phone, MessageReactions drops any of these the font cannot draw, then offers the rest plus "none"
in one Jev choice. Lines are `emoji<TAB>name[<TAB>hint]`; HINTS adds chat meanings (🐮 = 牛).

Usage: scripts/curate-reaction-emoji.py [saved-frequency-page.html]   (default: download)
"""
import html
import os
import re
import sys
import urllib.request

URL = "https://home.unicode.org/emoji/emoji-frequency/"
COUNT = 240
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "assets", "emoji", "emoji-catalog.tsv")
OUT = os.path.join(ROOT, "assets", "emoji", "reactions.tsv")

# Subgroups kept whole (minus DENY), in ranking order.
WHOLE = {
    "face-smiling", "face-affection", "face-tongue", "face-hand", "face-neutral-skeptical",
    "face-sleepy", "face-hat", "face-glasses", "face-concerned", "cat-face", "monkey-face", "heart",
    "hands", "hand-fingers-partial", "hand-fingers-closed", "hand-fingers-open", "event",
    "award-medal", "music",
}
# Individually kept from other subgroups, in ranking order.
PICK = set("""😵‍💫 🤯 🥴 💀 😤 🤖 👻 💯 💥 💫 💤 👀 💪 🧠 🫂 🤦 🤷 🙋 🙆 🙇 💁 🙅 💃 🕺 🫡 🔥 ⭐ 🌟 ☀️ 🌈
⚡ ❄️ 🌙 🌞 🌹 🌸 💐 🌷 🌻 🌺 🌼 🍀 🌱 🎂 🍰 🧁 🍕 ☕ 🍻 🥂 🍾 🍿 🍩 🍪 🍫 🍦 🦄 🐐 🦋 🐶 🐱 ✅ ✔️ ‼️ ❗ ❓
⁉️ 💰 💸 🎯 🏁 🚀 💎 👑 🎶 🤑 ✍️ 💡 🎓 💍 👶 ✈️ 🏖️ 🌍 📚 💻 🎮 ⚽ 🏀 🍜 🍣 🍔 🍷 🍺 📸 🎨 🧋 🐮""".split())
# Crude, hostile, alarming, dismissive or low-value as a reaction.
DENY = set("""🖕 💩 🤡 👹 👺 🤬 😡 👿 😈 ☠️ 💋 👎 💦 🍆 🍑 🤮 🤢 🤧 🤒 🤕 😷 🥵 🥶 👅 🔫 💣 🗡️ 🩸 ✊ 🤛 🤜
🫳 🫴 🤚 🫷 🫸 🫦 😾 😿 🙀 😼 🤥 📻 🎏 🎫 🎋 🎟️ 🎖️ 🎎 🎐 🎍 🎚️ 🎑 🎛️ 🎗️ 🎙️ 🥈 🥉 🖐️ 🖖 ✋ 🎼""".split())
# Emoji 11.0+ chat staples the 2021 ranking does not cover, placed after the top 40.
NEW = """🥰 🥺 🥳 🤍 ❤️‍🔥 🫶 🤌 🫂 🥹 🫠 🙂‍↕️ 😮‍💨 ❤️‍🩹 🥲 🫡 🫢 🫣 🤎 🩷 🩵 🩶 🫨 🥱 😶‍🌫️ 😵‍💫
🪩""".split()
# Chat meanings Jev cannot infer from the Unicode name, appended to its description.
HINTS = {
    "🐮": "Chinese chat slang 牛 (niú): awesome, impressive, well done",
}


def ranking(page: str) -> list:
    text = re.sub(r"<script.*?</script>|<style.*?</style>", "", page, flags=re.S)
    text = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", text)))
    marker = "Ranked Order by Median Frequency"
    if marker not in text:
        sys.exit("frequency ranking not found")
    body = text[text.index(marker) + len(marker):]
    body = body[:re.search(r"[A-Za-z]{4,}", body).start()]
    return [token for token in body.split() if not token.isdigit()]


def main() -> None:
    if len(sys.argv) > 1:
        with open(sys.argv[1], encoding="utf-8") as source:
            page = source.read()
    else:
        with urllib.request.urlopen(URL) as response:
            page = response.read().decode("utf-8")
    catalog = {}
    with open(CATALOG, encoding="utf-8") as source:
        for line in source:
            if not line.startswith("#"):
                group, subgroup, emoji, name, toned = line.rstrip("\n").split("\t")
                catalog[emoji] = (group, subgroup, name, toned == "1")
    chosen = []

    def add(emoji):
        if len(chosen) < COUNT and emoji in catalog and emoji not in chosen and emoji not in DENY \
                and not catalog[emoji][3]:
            chosen.append(emoji)

    for token in ranking(page):
        emoji = token if token in catalog else token + "️"
        if emoji not in catalog:
            continue
        group, subgroup, name, _ = catalog[emoji]
        gendered = name.startswith(("man ", "woman ", "men ", "women ")) or "♀" in emoji or "♂" in emoji
        if group != "Flags" and not gendered and (subgroup in WHOLE or emoji in PICK):
            add(emoji)
        if len(chosen) == 40:
            for extra in NEW:
                add(extra)
    for extra in NEW:
        add(extra)
    if len(chosen) != COUNT:
        sys.exit(f"curated {len(chosen)} emoji, expected {COUNT}")
    with open(OUT, "w", encoding="utf-8") as out:
        out.write("# Jev's reaction choices: Unicode frequency order, curated for chat; "
                  "regenerate with scripts/curate-reaction-emoji.py\n")
        out.writelines("\t".join([emoji, catalog[emoji][2]] + ([HINTS[emoji]] if emoji in HINTS else [])) + "\n"
                       for emoji in chosen)
    print(f"{len(chosen)} emoji -> {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
