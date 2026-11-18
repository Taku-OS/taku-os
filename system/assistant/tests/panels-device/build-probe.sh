#!/bin/bash
# Build only. The owning session decides when to install or run the probe.
# The APK is signed with a key generated once in out/ for this probe, never the platform
# key, and is debuggable so the host can pull the probe's full evidence file with run-as.
set -euo pipefail
cd "$(dirname "$0")/../../../.."
source scripts/android-env.sh

OUTPUT="$PWD/out/panels-device/probe"
if [ "$#" -gt 0 ]; then
  if [ "$#" -ne 2 ] || [ "$1" != "--output" ]; then
    echo "Usage: $0 [--output DIRECTORY]" >&2
    exit 2
  fi
  OUTPUT="$2"
fi
command -v openssl >/dev/null 2>&1 || { echo "openssl is required to generate the probe key" >&2; exit 1; }
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex" "$OUTPUT/signing"
SOURCE="system/assistant/tests/panels-device/org/agentos/panelsprobe/PanelsProbe.java"
PACKAGE="org.agentos.panelsprobe"
SIGNING="$OUTPUT/signing"

"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/classes" "$SOURCE"
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"

cat > "$OUTPUT/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PACKAGE" android:versionCode="1" android:versionName="1.0">
 <uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/>
 <application android:label="Taku Panels device probe" android:allowBackup="false" android:debuggable="true"/>
 <instrumentation android:name="$PACKAGE.PanelsProbe" android:targetPackage="$PACKAGE" android:functionalTest="true" android:handleProfiling="false"/>
</manifest>
XML
"$TOOLS/aapt2" link -o "$OUTPUT/resources.apk" -I "$ANDROID_JAR" --manifest "$OUTPUT/AndroidManifest.xml"
cp "$OUTPUT/resources.apk" "$OUTPUT/unsigned.apk"
(cd "$OUTPUT/dex" && zip -q "$OUTPUT/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -P 16 -f 4 "$OUTPUT/unsigned.apk" "$OUTPUT/aligned.apk"

key_generated=no
if [ ! -f "$SIGNING/probe-key.pem" ] || [ ! -f "$SIGNING/probe-cert.pem" ]; then
  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout "$SIGNING/probe-key.pem.tmp" -out "$SIGNING/probe-cert.pem.tmp" \
    -subj "/CN=Taku Panels Device Probe/O=TakuOS Development" >/dev/null 2>&1
  chmod 600 "$SIGNING/probe-key.pem.tmp"
  mv "$SIGNING/probe-key.pem.tmp" "$SIGNING/probe-key.pem"
  mv "$SIGNING/probe-cert.pem.tmp" "$SIGNING/probe-cert.pem"
  key_generated=yes
fi
openssl pkcs8 -topk8 -nocrypt -in "$SIGNING/probe-key.pem" -outform DER -out "$SIGNING/probe-key.pk8"
chmod 600 "$SIGNING/probe-key.pk8"

JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key "$SIGNING/probe-key.pk8" --cert "$SIGNING/probe-cert.pem" \
  --out "$OUTPUT/panels-probe.apk" "$OUTPUT/aligned.apk"
"$TOOLS/apksigner" verify --print-certs "$OUTPUT/panels-probe.apk" > "$OUTPUT/signer.txt"
if "$TOOLS/aapt2" dump badging "$OUTPUT/panels-probe.apk" | grep -q "uses-permission"; then
  echo "Probe APK declares a permission; it must stay a zero-permission ordinary app" >&2
  exit 1
fi

cert_digest="$(openssl x509 -in "$SIGNING/probe-cert.pem" -outform DER | openssl dgst -sha256 -r | cut -d' ' -f1)"
signer_count="$(grep -Ec '^(Signer #[0-9]+|V3\.0 Signer):? certificate DN:' "$OUTPUT/signer.txt" || true)"
signer_digest="$(sed -nE 's/^(Signer #[0-9]+|V3\.0 Signer):? certificate SHA-256 digest: //p' "$OUTPUT/signer.txt")"
signer_dn="$(sed -nE 's/^(Signer #[0-9]+|V3\.0 Signer):? certificate DN: //p' "$OUTPUT/signer.txt")"
if [ "$signer_count" != "1" ]; then
  echo "Expected one signer in the probe APK, found $signer_count" >&2
  exit 1
fi
if [ "$signer_digest" != "$cert_digest" ]; then
  echo "Probe APK signer does not match the generated development certificate" >&2
  exit 1
fi
case "$signer_dn" in
  *"Taku Panels Device Probe"*) ;;
  *) echo "Probe APK is not signed by the generated development certificate: $signer_dn" >&2; exit 1 ;;
esac
if command -v shasum >/dev/null 2>&1; then
  apk_digest="$(shasum -a 256 "$OUTPUT/panels-probe.apk" | cut -d' ' -f1)"
else
  apk_digest="$(openssl dgst -sha256 -r "$OUTPUT/panels-probe.apk" | cut -d' ' -f1)"
fi
apk_bytes="$(wc -c < "$OUTPUT/panels-probe.apk" | tr -d ' ')"
python3 - "$OUTPUT/signing.json" <<PY
import json, sys
json.dump({"package": "$PACKAGE", "apk": "$OUTPUT/panels-probe.apk", "apkBytes": int("$apk_bytes"),
           "apkSha256": "$apk_digest", "signerSha256": "$signer_digest", "signerDn": "$signer_dn",
           "keyGeneratedThisBuild": "$key_generated" == "yes"}, open(sys.argv[1], "w"), indent=2)
PY

echo "APK=$OUTPUT/panels-probe.apk"
echo "APK_BYTES=$apk_bytes"
echo "APK_SHA256=$apk_digest"
echo "SIGNER_SHA256=$signer_digest"
echo "SIGNER_DN=$signer_dn"
echo "KEY_GENERATED=$key_generated"
echo "Built the independently signed probe only; no device was modified."
