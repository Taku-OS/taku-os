#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source ../../../scripts/android-env.sh
bash ../build.sh
OUT="$PWD/../out/plugin"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/install/skills/country"
SDK_JAR="$PWD/../out/taku-environment-signals.jar"
"$JDK/bin/javac" --release 17 -encoding UTF-8 -cp "$ANDROID_JAR:$SDK_JAR" -d "$OUT/classes" src/org/agentos/environment/plugin/CountryProvider.java
"$JDK/bin/jar" --create --file "$OUT/provider-classes.jar" -C "$OUT/classes" .
"$TOOLS/d8" --release --min-api 36 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/provider-classes.jar" "$SDK_JAR"
"$JDK/bin/jar" --create --file "$OUT/install/country-provider.jar" -C "$OUT/dex" classes.dex
cp manifest.json start.sh "$OUT/install/"
cp skills/country/SKILL.md "$OUT/install/skills/country/"
chmod 755 "$OUT/install/start.sh"
python3 - "$OUT" <<'PY'
from pathlib import Path
import hashlib
import sys
import zipfile
out = Path(sys.argv[1])
files = sorted(path for path in (out / 'install').rglob('*') if path.is_file())
with zipfile.ZipFile(out / 'country-plugin-0.1.0.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in files:
        archive.write(path, path.relative_to(out / 'install'))
with (out / 'SHA256SUMS').open('w') as receipt:
    for path in [*files, out / 'country-plugin-0.1.0.zip']:
        receipt.write(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(out)}\n')
PY
node ../../../sdk/plugins/validate.mjs manifest.json
printf 'Plugin directory: %s\nPlugin zip: %s\n' "$OUT/install" "$OUT/country-plugin-0.1.0.zip"
