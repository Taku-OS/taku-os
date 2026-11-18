#!/system/bin/sh
set -eu
stamp_ms() {
  read uptime_seconds rest < /proc/uptime
  whole=${uptime_seconds%%.*}
  fraction=${uptime_seconds#*.}
  echo "$((whole * 1000 + (1000$fraction % 100) * 10))"
}
LAB=/data/local/agentos/light-app-lab
KIT="$LAB/free-app-kit"
APP="$1"
PKG="$2"
case "$APP" in *[!a-zA-Z0-9_-]*|'') exit 2;; esac
case "$PKG" in *[!a-zA-Z0-9_.]*|'') exit 2;; esac
NAME="${3:-Generated App}"
export LD_LIBRARY_PATH=/data/local/agentos/light-app-lab/native/lib
export PATH="$LAB/native:/apex/com.android.art/bin:/system/bin:/system_ext/bin"
PROJECT="$KIT/apps/$APP"
cd "$PROJECT"
START=$(stamp_ms)
mkdir -p apk/assets/runtime apk/res/values apk/res/xml
printf '%s' "$APP" > apk/assets/app-id.txt
printf '%s' '<network-security-config><domain-config cleartextTrafficPermitted="true"><domain>127.0.0.1</domain></domain-config></network-security-config>' > apk/res/xml/network_security_config.xml
cp app.bundle.js apk/assets/app.bundle.js
cp "$KIT/runtime/index.html" "$KIT/runtime/react.js" "$KIT/runtime/react-dom.js" apk/assets/runtime/
node -e 'const fs=require("fs");const s=process.argv[1].replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");fs.writeFileSync("apk/res/values/strings.xml","<resources><string name=\"app_name\">"+s+"</string></resources>")' "$NAME"
cat > apk/AndroidManifest.xml <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG" android:versionCode="1" android:versionName="1.0"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35"/><uses-permission android:name="android.permission.INTERNET"/><uses-permission android:name="android.permission.POST_NOTIFICATIONS"/><application android:label="@string/app_name" android:theme="@android:style/Theme.Material.Light.NoActionBar" android:allowBackup="false" android:networkSecurityConfig="@xml/network_security_config"><activity android:name="org.agentos.generated.MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>
EOF
aapt2 compile --dir apk/res -o apk/res.zip
aapt2 link -I "$LAB/toolchain/android.jar" --manifest apk/AndroidManifest.xml -A apk/assets apk/res.zip -o apk/unsigned.apk
cp "$KIT/apk-shell/out/dex/classes.dex" apk/classes.dex
cd apk
aapt add -f unsigned.apk classes.dex
zipalign -f 4 unsigned.apk aligned.apk
dalvikvm -cp "$LAB/toolchain/apksigner.dex" com.android.apksigner.ApkSignerTool sign --key "$LAB/toolchain/debug.pk8" --cert "$LAB/toolchain/debug.x509.pem" --out app.apk aligned.apk
dalvikvm -cp "$LAB/toolchain/apksigner.dex" com.android.apksigner.ApkSignerTool verify app.apk
END=$(stamp_ms)
echo "device_apk_pack_ms=$(((END-START)/1))"
pm install -r app.apk
pm grant "$PKG" android.permission.POST_NOTIFICATIONS
am start --display 0 -n "$PKG/org.agentos.generated.MainActivity"
